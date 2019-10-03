from typing import Generator

import docker as docker_py
import pytest
import shutil

from casperlabs_local_net.common import make_tempdir, random_string
from casperlabs_local_net.casperlabs_network import (
    CustomConnectionNetwork,
    OneNodeNetwork,
    ThreeNodeNetwork,
    TwoNodeNetwork,
    PaymentNodeNetwork,
    PaymentNodeNetworkWithNoMinBalance,
    TrillionPaymentNodeNetwork,
    OneNodeWithGRPCEncryption,
    EncryptedTwoNodeNetwork,
    ReadOnlyNodeNetwork,
)
from docker.client import DockerClient


@pytest.fixture(scope="function")
def deleting_temp_dir():
    directory = make_tempdir(random_string(6))
    yield directory
    shutil.rmtree(directory)


@pytest.fixture(scope="session")
def docker_client_fixture() -> Generator[DockerClient, None, None]:
    docker_client = docker_py.from_env()
    try:
        yield docker_client
    finally:
        docker_client.volumes.prune()
        docker_client.networks.prune()


@pytest.fixture(scope="module")
def one_node_network(docker_client_fixture):
    with OneNodeNetwork(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture(scope="module")
def read_only_node_network(docker_client_fixture):
    with ReadOnlyNodeNetwork(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture(scope="function")
def one_node_network_fn(docker_client_fixture):
    with OneNodeNetwork(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture(scope="function")
def payment_node_network(docker_client_fixture):
    with PaymentNodeNetwork(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture(scope="function")
def trillion_payment_node_network(docker_client_fixture):
    with TrillionPaymentNodeNetwork(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture(scope="function")
def payment_node_network_no_min_balance(docker_client_fixture):
    with PaymentNodeNetworkWithNoMinBalance(docker_client_fixture) as onn:
        onn.create_cl_network()
        yield onn


@pytest.fixture(scope="function")
def encrypted_one_node_network(docker_client_fixture):
    with OneNodeWithGRPCEncryption(docker_client_fixture) as net:
        net.create_cl_network()
        yield net


@pytest.fixture()
def two_node_network(docker_client_fixture):
    with TwoNodeNetwork(docker_client_fixture) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture()
def encrypted_two_node_network(docker_client_fixture):
    with EncryptedTwoNodeNetwork(docker_client_fixture) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture(scope="module")
def three_node_network(docker_client_fixture):
    with ThreeNodeNetwork(docker_client_fixture) as tnn:
        tnn.create_cl_network()
        yield tnn


@pytest.fixture(scope="module")
def nodes(three_node_network):
    return three_node_network.docker_nodes


@pytest.fixture(scope="module")
def node(one_node_network):
    return one_node_network.docker_nodes[0]


@pytest.fixture(scope="module")
def engine(one_node_network):
    with one_node_network as network:
        yield network.execution_engines[0]


@pytest.fixture()
def star_network(docker_client_fixture):
    with CustomConnectionNetwork(docker_client_fixture) as ccn:
        node_count = 4
        network_connections = [[0, n] for n in range(1, 4)]
        ccn.create_cl_network(
            node_count=node_count, network_connections=network_connections
        )
        yield ccn


@pytest.hookimpl(tryfirst=True)
def pytest_keyboard_interrupt(excinfo):
    docker_client = docker_py.from_env()
    docker_client.containers.prune()
    docker_client.volumes.prune()
    docker_client.networks.prune()
    pytest.exit("Keyboard Interrupt occurred, So stopping the execution of tests.")
