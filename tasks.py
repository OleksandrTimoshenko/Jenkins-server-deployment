import os
from invoke import task
from dotenv import load_dotenv, dotenv_values
import docker

load_dotenv()

AWS_ID = os.getenv("AWS_ID")
AWS_KEY = os.getenv("AWS_KEY")
AWS_REGION = os.getenv("AWS_REGION")
AWS_BUCKET = os.getenv("AWS_BUCKET")

registry = os.getenv("GITHUB_USER").lower()


def logger(log_str):
    length = len(log_str)
    print("_" * (length + 4))
    print(f"| {log_str} |")
    print("-" * (length + 4))


def get_container_id_by_name(container_name):
    client = docker.from_env()
    containers = client.containers.list(filters={"name": container_name})
    if containers:
        return containers[0].id
    return None


def check_that_aws_vars_exist():
    env_variables = dotenv_values('.env')
    required_variables = ['AWS_ID', 'AWS_KEY', 'AWS_REGION', 'AWS_BUCKET']
    missing_variables = [
        variable for variable in required_variables if variable not in env_variables]
    if len(missing_variables) == 0:
        return True

    logger(
        f"WARNING: You cannot download/upload backups to/from \
        the bucket because these variables are missing: {missing_variables}")
    return False


@task
def swarm_exist(context):
    status = context.run(
        "docker info | grep Swarm | sed 's/Swarm: //g'",
        hide=True).stdout.replace(
        " ",
        "").replace(
            "\n",
        "")
    return status == "active"


@task
def registry_login(context):
    username = os.getenv("GITHUB_USER")
    token = os.getenv("GITHUB_TOKEN")
    context.run(f"docker login -u {username} -p {token} ghcr.io")


@task
def build_master(context):
    context.run(
        f"docker build -t ghcr.io/{registry}/jenkins-master:latest \
        -f ./docker/Dockerfile_master ./docker")


@task
def build_terraform(context):
    terraform_version = os.getenv("TERRAFORM_VERSION")
    context.run(
        f"docker build --build-arg TERRAFORM_VERSION={terraform_version} \
        -t ghcr.io/{registry}/jenkins-slave-terraform:latest \
        -f ./docker/Dockerfile_terraform_worker ./docker")


@task
def build_images(context):
    build_master(context)
    build_terraform(context)


@task
def push_master_image(context):
    build_master(context)
    registry_login(context)
    context.run(f"docker push ghcr.io/{registry}/jenkins-master:latest")


@task
def push_terraform_image(context):
    build_terraform(context)
    registry_login(context)
    context.run(f"docker push ghcr.io/{registry}/jenkins-slave-terraform:latest")


@task
def push_images(context):
    push_master_image(context)
    push_terraform_image(context)


@task
def init_swarm(context):
    context.run("docker swarm init --advertise-addr 192.168.0.103")


@task
def start(context):
    """
    Create a Jenkins server

    This task can will deploy swarm stack with Jenkins server
    """
    registry_login(context)
    if check_that_aws_vars_exist():
        download_backup = input(
            "Do you want to download backup from AWS bucket? Y/N ")
        if download_backup.lower() == "y":
            context.run(
                f"python3 ./data.py -i {AWS_ID} -k {AWS_KEY} \
                -r {AWS_REGION} -b {AWS_BUCKET} -f data -m S3_TO_LOCAL")

    if not os.path.exists("./data"):
        os.makedirs("./data")
    try:
        context.run(f"docker pull ghcr.io/{registry}/jenkins-master:latest")
    except BaseException:
        logger("Error while pull jenkins-master image! Trying to push...")
        push_master_image(context)
    try:
        context.run(f"docker pull ghcr.io/{registry}/jenkins-slave-terraform:latest")
    except BaseException:
        logger("Error while pull jenkins-slave-terraform image! Trying to push...")
        push_terraform_image(context)
    if not swarm_exist(context):
        logger("Creating swarm...")
        context.run("docker swarm init --advertise-addr 192.168.0.103")
    context.run(
        f"REGISTRY={registry} docker stack deploy --compose-file docker-compose.yaml jenkins")


@task
def get_password(context):
    """
    Get password for Jenkins first setup
    """
    master_container_id = get_container_id_by_name("jenkins-master")
    if master_container_id is None:
        logger(
            "Something goes wrong, it seems like jenkins-master container doesn`t exist...")
    else:
        try:
            first_time_pass = context.run(
                f"docker exec -t {master_container_id} \
                cat /var/jenkins_home/secrets/initialAdminPassword",
                hide=True).stdout.replace(
                "\n",
                "")
            logger(f"First time jenkins password: {first_time_pass}")
        except BaseException:
            logger("It seems like first time jenkins password is not available anymore")


@task
def destroy(context):
    """
    Destroy stask and leave swarm
    """
    try:
        context.run("docker swarm leave -f")
    except BaseException:
        logger("This node is not part of a swarm")
    delete_volumes = input("Do you want to delete volumes? Y/N ")
    if delete_volumes.lower() == "y":
        context.run("sudo rm -rf ./data")


@task
def create_backup(context):
    """
    Create a Jenkins info backup and copy it to AWS S3
    """
    if check_that_aws_vars_exist():
        context.run(
            f"python3 ./data.py -i {AWS_ID} -k {AWS_KEY} \
            -r {AWS_REGION} -b {AWS_BUCKET} -f data -m LOCAL_TO_S3")
    else:
        logger("It seems like something goes wrong, pls check warnings...")
