package io.casperlabs.comm.gossiping

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.GossipError
import io.casperlabs.shared.Log
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import java.util.concurrent.atomic.AtomicInteger
import monix.eval.Task
import monix.execution.Scheduler
import monix.tail.Iterant
import org.scalatest._
import org.scalatest.concurrent._
import org.scalacheck.{Arbitrary, Gen}, Arbitrary.arbitrary
import scala.concurrent.duration._

class DownloadManagerSpec
    extends WordSpecLike
    with Matchers
    with BeforeAndAfterEach
    with ArbitraryConsensus {

  import DownloadManagerSpec._
  import Scheduler.Implicits.global

  // Collect log messages. Reset before each test.
  implicit val log = new LogStub[Task]()

  override def beforeEach() =
    log.reset()

  // A generators .sample.get can sometimes return None, but these examples have no reason to not generate a result,
  // so defend against that and retry if it does happen.
  def sample[T](g: Gen[T]): T = {
    def loop(i: Int): T = {
      assert(i > 0, "Should be able to generate a sample.")
      g.sample.fold(loop(i - 1))(identity)
    }
    loop(10)
  }

  // Create a random Node so I don't have to repeat this in all tests.
  val source = sample(arbitrary[Node])

  // Don't have to create big blocks because it takes ages.
  implicit val consensusConfig =
    ConsensusConfig(dagSize = 10, maxSessionCodeBytes = 50, maxPaymentCodeBytes = 10)

  "DownloadManager" when {
    "scheduled to download a section of the DAG" should {
      // Make sure the genesis has more than 1 child so we can download them in parallel. This is easy to check.
      val dag = sample(
        genBlockDag
          .retryUntil { blocks =>
            (blocks(1).getHeader.parentHashes.toSet & blocks(2).getHeader.parentHashes.toSet).nonEmpty
          }
      )
      val blockMap = toBlockMap(dag)
      val remote   = MockGossipService(dag)

      def scheduleAll(manager: DownloadManager[Task]): Task[List[Task[Unit]]] =
        // Add them in the natural topological order they were generated with.
        dag.toList.traverse { block =>
          manager.scheduleDownload(summaryOf(block), source, relay = false)
        }

      def awaitAll(watches: List[Task[Unit]]): Task[Unit] =
        watches.traverse(identity).void

      "eventually download the full DAG" in TestFixture(remote = _ => remote) {
        case (manager, backend) =>
          for {
            ws <- scheduleAll(manager)
            _  <- awaitAll(ws)
          } yield {
            backend.blocks should contain theSameElementsAs dag.map(_.blockHash)
          }
      }

      "download blocks in topological order" in TestFixture(
        remote = _ =>
          MockGossipService(
            dag,
            // Delay just the genesis block a little so we can schedule everything before the first download finishes.
            regetter = _ flatMap {
              case Some(block) if block == dag.head =>
                Task.pure(Some(block)).delayResult(250.millis)
              case other => Task.pure(other)
            }
          ),
        maxParallelDownloads = consensusConfig.dagSize
      ) {
        case (manager, backend) =>
          for {
            watches <- scheduleAll(manager)
            _       = backend.blocks shouldBe empty // So we know it hasn't done them immedately during the scheduling.
            _       <- awaitAll(watches)
          } yield {
            // All parents should be stored before their children.
            Inspectors.forAll(backend.blocks.zipWithIndex) {
              case (blockHash, idx) =>
                val followed = backend.blocks.drop(idx + 1).toSet
                val deps     = dependenciesOf(blockMap(blockHash)).toSet
                followed intersect deps shouldBe empty
            }
          }
      }

      def checkParallel(maxParallelDownloads: Int)(test: Int => Unit): Unit = {
        val parallelNow = new AtomicInteger(0)
        val parallelMax = new AtomicInteger(0)
        TestFixture(
          remote = _ =>
            MockGossipService(
              dag,
              regetter = task => {
                // Draw it out a little bit so it can start multiple downloads.
                Task.delay(parallelNow.incrementAndGet()) *> task.delayResult(100.millis)
              },
              rechunker = _.map { chunk =>
                parallelMax.set(math.max(parallelNow.get, parallelMax.get))
                chunk
              }
            ),
          backend = MockBackend(validate = _ => Task.delay(parallelNow.decrementAndGet()).void),
          maxParallelDownloads = maxParallelDownloads
        ) {
          case (manager, _) =>
            for {
              ws <- scheduleAll(manager)
              _  <- awaitAll(ws)
            } yield {
              test(parallelMax.get)
            }
        }
      }

      "download blocks in parallel if possible" in {
        checkParallel(maxParallelDownloads = consensusConfig.dagSize) {
          _ should be > 1
        }
      }

      "not exceed the limit on parallelism" in {
        checkParallel(maxParallelDownloads = 1) {
          _ shouldBe 1
        }
      }
    }

    "scheduled to download a block with missing dependencies" should {
      val block = sample(arbitrary[Block].suchThat(_.getHeader.parentHashes.nonEmpty))

      "raise an error" in TestFixture(MockBackend(), _ => MockGossipService(Seq(block))) {
        case (manager, _) =>
          manager.scheduleDownload(summaryOf(block), source, false).attempt.map { result =>
            result.isLeft shouldBe true
            result.left.get shouldBe a[GossipError.MissingDependencies]
          }
      }
    }

    "scheduled to download a block which already exists" should {
      val block = sample(arbitrary[Block])

      "skip the download" in TestFixture() {
        case (manager, backend) =>
          for {
            _     <- backend.storeBlock(block)
            watch <- manager.scheduleDownload(summaryOf(block), source, false)
            _     <- watch
          } yield {
            backend.blocks should have size 1
          }
      }
    }

    "scheduled to download a block which is already downloading" should {
      val block = arbitrary[Block].sample.map(withoutDependencies(_)).get
      // Delay a little so it can start two downloads if it's buggy.
      val remote = MockGossipService(Seq(block), regetter = _.delayResult(100.millis))

      "not download twice" in TestFixture(remote = _ => remote) {
        case (manager, backend) =>
          for {
            // Schedule twice in a row, rapidly.
            watch1 <- manager.scheduleDownload(summaryOf(block), source, false)
            watch2 <- manager.scheduleDownload(summaryOf(block), source, false)
            // Wait until both report they are complete.
            _ <- watch1
            _ <- watch2
          } yield {
            backend.blocks should have size 1
          }
      }
    }

    "released as a resource" should {
      val block = arbitrary[Block].sample.map(withoutDependencies).get

      "cancel outstanding downloads" in {
        @volatile var started  = false
        @volatile var finished = false
        val remote =
          MockGossipService(
            Seq(block),
            regetter = { get =>
              for {
                _ <- Task.delay({ started = true })
                _ <- Task.sleep(750.millis)
                _ <- Task.delay({ finished = true })
                r <- get
              } yield r
            }
          )
        val backend = MockBackend()

        val test = for {
          alloc <- DownloadManagerImpl[Task](
                    maxParallelDownloads = 1,
                    connectToGossip = _ => remote,
                    backend = backend
                  ).allocated
          (manager, release) = alloc
          _                  <- manager.scheduleDownload(summaryOf(block), source, relay = false)
          _                  <- Task.sleep(500.millis)
          _                  <- release
          _                  <- Task.sleep(500.millis)
        } yield {
          backend.summaries should not contain (block.blockHash)
          started shouldBe true
          finished shouldBe false
        }

        test.runSyncUnsafe(2.seconds)
      }

      "reject further schedules" in {
        val test = for {
          alloc <- DownloadManagerImpl[Task](
                    maxParallelDownloads = 1,
                    connectToGossip = _ => MockGossipService(),
                    backend = MockBackend()
                  ).allocated
          (manager, release) = alloc
          _                  <- release
          res                <- manager.scheduleDownload(summaryOf(block), source, relay = false).attempt
        } yield {
          res.isLeft shouldBe true
          res.left.get shouldBe a[java.lang.IllegalStateException]
        }

        test.runSyncUnsafe(1.seconds)
      }
    }

    "fails to download a block from the first source" should {
      val block = sample(arbitrary[Block].map(withoutDependencies(_)))
      val nodeA = sample(arbitrary[Node])
      val nodeB = sample(arbitrary[Node])

      val remote = (node: Node) =>
        node match {
          case `nodeA` =>
            MockGossipService(
              Seq(block),
              regetter = _.delayResult(100.millis) *> Task.raiseError(
                new Exception("Node A is dying!")
              )
            )
          case _ => MockGossipService(Seq(block))
        }

      "try to download the block from a different source" in TestFixture(remote = remote) {
        case (manager, backend) =>
          for {
            w1 <- manager.scheduleDownload(summaryOf(block), nodeA, false)
            w2 <- manager.scheduleDownload(summaryOf(block), nodeB, false)
            // Both should be successful eventually.
            _ <- w1
            _ <- w2
          } yield {
            log.causes should have size 1
            log.causes.head.getMessage shouldBe "Node A is dying!"
            backend.blocks should contain(block.blockHash)
          }
      }
    }

    "downloaded a valid block" should {
      val block   = arbitrary[Block].sample.map(withoutDependencies).get
      val remote  = MockGossipService(Seq(block))
      def backend = MockBackend()

      def check(test: MockBackend => Unit): TestArgs => Task[Unit] = {
        case (manager, backend) =>
          for {
            w <- manager.scheduleDownload(summaryOf(block), source, relay = true)
            _ <- w
          } yield {
            test(backend)
          }
      }

      "validate the block" in TestFixture(backend, _ => remote) {
        check(_.validations should contain(block.blockHash))
      }

      "store the block" in TestFixture(backend, _ => remote) {
        check(_.blocks should contain(block.blockHash))
      }

      "store the block summary" in TestFixture(backend, _ => remote) {
        check(_.summaries should contain(block.blockHash))
      }

      "gossip to other nodes" in (pending)
    }

    "cannot validate a block" should {
      val dag    = sample(genBlockDag)
      val remote = MockGossipService(dag)
      def backend =
        MockBackend(_ => Task.raiseError(new java.lang.IllegalArgumentException("Nope.")))

      "not store the block" in TestFixture(backend, _ => remote) {
        case (manager, backend) =>
          val block = dag.head
          for {
            w <- manager.scheduleDownload(summaryOf(block), source, false)
            _ <- w.attempt
          } yield {
            backend.validations should contain(block.blockHash)
            backend.blocks should not contain (block.blockHash)
            Inspectors.forExactly(1, log.causes) {
              _.getMessage shouldBe "Nope."
            }
          }
      }

      "not download the dependant blocks" in TestFixture(backend, _ => remote) {
        case (manager, backend) =>
          for {
            w <- manager.scheduleDownload(summaryOf(dag(0)), source, false)
            _ <- manager.scheduleDownload(summaryOf(dag(1)), source, false)
            _ <- w.attempt
            _ <- Task.sleep(250.millis) // 2nd should never be attempted.
          } yield {
            backend.validations should contain(dag(0).blockHash)
            backend.validations should not contain (dag(1).blockHash)
          }
      }
    }

    "cannot connect to a node" should {
      val block = arbitrary[Block].sample.map(withoutDependencies).get

      "try again if the same block from the same source is scheduled again after the previous attempt is finished" in TestFixture(
        remote = _ => Task.raiseError(io.grpc.Status.UNAVAILABLE.asRuntimeException())
      ) {
        case (manager, _) =>
          for {
            w1 <- manager.scheduleDownload(summaryOf(block), source, false)
            r1 <- w1.attempt
            w2 <- manager.scheduleDownload(summaryOf(block), source, false)
            r2 <- w2.attempt
          } yield {
            Inspectors.forAll(Seq(r1, r2)) { r =>
              r.isLeft shouldBe true
              r.left.get shouldBe an[io.grpc.StatusRuntimeException]
            }
            log.causes should have size 2
            log.causes.head shouldBe r1.left.get
            log.causes.last shouldBe r2.left.get
          }
      }

      "try again later with exponential backoff" in (pending)
    }

    "receiving chunks" should {
      val block = arbitrary[Block].sample.map(withoutDependencies).get

      def check(args: TestArgs, msg: String): Task[Unit] =
        for {
          w <- args._1.scheduleDownload(summaryOf(block), source, false)
          _ <- w.attempt
        } yield {
          Inspectors.forExactly(1, log.causes) { ex =>
            ex shouldBe a[GossipError.InvalidChunks]
            ex.getMessage should include(msg)
          }
        }

      def withRechunking(f: Iterant[Task, Chunk] => Iterant[Task, Chunk]) = { (node: Node) =>
        MockGossipService(Seq(block), rechunker = f)
      }

      def rewriteHeader(
          f: Chunk.Header => Chunk.Header
      ): Iterant[Task, Chunk] => Iterant[Task, Chunk] = { it =>
        it.take(1).map { chunk =>
          assert(chunk.content.isHeader)
          chunk.withHeader(f(chunk.getHeader))
        } ++ it.tail
      }

      "check that they start with the header" in TestFixture(
        remote = withRechunking(_.tail)
      )(check(_, "did not start with a header"))

      "check that the total size doesn't exceed promises" in TestFixture(
        remote = withRechunking(rewriteHeader(_.copy(contentLength = 0)))
      )(check(_, "exceeding the promised content length"))

      "check that the expected compression algorithm is used" in TestFixture(
        remote = withRechunking(rewriteHeader(_.copy(compressionAlgorithm = "gzip")))
      )(check(_, "unexpected algorithm"))

      "handle an empty stream" in TestFixture(
        remote = withRechunking(_.take(0))
      )(check(_, "not receive a header"))

      "handle a stream that ends prematurely" in TestFixture(
        remote = withRechunking(_.take(1))
      )(check(_, "not decompress"))
    }

    "cannot query the backend" should {
      val block1 = sample(arbitrary[Block])
      val block2 = sample(arbitrary[Block].map(withoutDependencies))
      val remote = MockGossipService(Seq(block1, block2))
      val backend = new MockBackend() {
        override def hasBlock(blockHash: ByteString) =
          if (blockHash == block1.blockHash || dependenciesOf(block1).contains(blockHash))
            Task.raiseError(new RuntimeException("Oh no!"))
          else
            Task.pure(false)
      }

      "raise a scheduling error, but keep processing schedules" in TestFixture(backend, _ => remote) {
        case (manager, _) =>
          for {
            attempt1 <- manager.scheduleDownload(summaryOf(block1), source, false).attempt
            attempt2 <- manager.scheduleDownload(summaryOf(block2), source, false).attempt
          } yield {
            attempt1 match {
              case Left(ex: RuntimeException) =>
                ex.getMessage shouldBe "Oh no!"
              case other =>
                fail(s"Expected scheduling to fail; got $other")
            }
            attempt2.isRight shouldBe true
          }
      }
    }
  }
}

object DownloadManagerSpec {

  def summaryOf(block: Block): BlockSummary =
    BlockSummary()
      .withBlockHash(block.blockHash)
      .withHeader(block.getHeader)
      .withSignature(block.getSignature)

  def dependenciesOf(block: Block): Seq[ByteString] =
    block.getHeader.parentHashes ++ block.getHeader.justifications.map(_.latestBlockHash)

  def withoutDependencies(block: Block): Block =
    block.withHeader(block.getHeader.copy(parentHashes = Seq.empty, justifications = Seq.empty))

  def toBlockMap(blocks: Seq[Block]) =
    blocks.groupBy(_.blockHash).mapValues(_.head)

  type TestArgs = (DownloadManager[Task], MockBackend)

  object TestFixture {
    def apply(
        backend: MockBackend = MockBackend.default,
        remote: Node => Task[GossipService[Task]] = _ => MockGossipService.default,
        maxParallelDownloads: Int = 1
    )(
        test: TestArgs => Task[Unit]
    )(implicit scheduler: Scheduler, log: Log[Task]): Unit = {

      val managerR = DownloadManagerImpl[Task](
        maxParallelDownloads = maxParallelDownloads,
        connectToGossip = remote(_),
        backend = backend
      )

      val runTest = managerR.use { manager =>
        test(manager, backend)
      }

      runTest.runSyncUnsafe(5.seconds)
    }
  }

  class MockBackend(validate: Block => Task[Unit] = _ => Task.unit)
      extends DownloadManagerImpl.Backend[Task] {
    // Record what we have been called with.
    @volatile var validations = Vector.empty[ByteString]
    @volatile var blocks      = Vector.empty[ByteString]
    @volatile var summaries   = Vector.empty[ByteString]

    def hasBlock(blockHash: ByteString): Task[Boolean] =
      Task.now(blocks.contains(blockHash))

    def validateBlock(block: Block): Task[Unit] =
      Task.delay {
        synchronized { validations = validations :+ block.blockHash }
      } *> validate(block)

    def storeBlock(block: Block): Task[Unit] = Task.delay {
      synchronized { blocks = blocks :+ block.blockHash }
    }

    def storeBlockSummary(summary: BlockSummary): Task[Unit] = Task.delay {
      synchronized { summaries = summaries :+ summary.blockHash }
    }
  }
  object MockBackend {
    def default                                               = apply()
    def apply(validate: Block => Task[Unit] = _ => Task.unit) = new MockBackend(validate)
  }

  /** Test implementation of the remote GossipService to download the blocks from. */
  object MockGossipService {
    // Used only as a default argument for when we aren't touching the remote service in a test.
    val default = Task.now {
      new GossipServiceServer[Task](
        getBlock = _ => Task.now(None),
        getBlockSummary = _ => ???,
        maxChunkSize = 100 * 1024
      )
    }

    def apply(
        blocks: Seq[Block] = Seq.empty,
        // Pass in a `rechunker` method to alter the stream of block chunks returned by `getBlockChunked`.
        rechunker: Iterant[Task, Chunk] => Iterant[Task, Chunk] = identity,
        // Pass in a `regetter` method to alter the behaviour of the `getBlock`, for example to add delays.
        regetter: Task[Option[Block]] => Task[Option[Block]] = identity
    ) = Task.now {
      val blockMap = toBlockMap(blocks)
      new GossipServiceServer[Task](
        getBlock = hash => regetter(Task.delay(blockMap.get(hash))),
        getBlockSummary = hash => ???,
        maxChunkSize = 100 * 1024
      ) {
        override def getBlockChunked(request: GetBlockChunkedRequest) =
          rechunker(super.getBlockChunked(request))
      }
    }
  }
}
