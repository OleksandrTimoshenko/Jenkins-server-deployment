from invoke import task
from dotenv import load_dotenv
from dotenv import dotenv_values
import os
import docker

load_dotenv()

AWS_ID = os.getenv("AWS_ID")
AWS_KEY = os.getenv("AWS_KEY")
AWS_REGION = os.getenv("AWS_REGION")
AWS_BUCKET = os.getenv("AWS_BUCKET")

registry = os.getenv("GITHUB_USER").lower()

def logger(str):
    length = len(str)
    print("_" * (length + 4))
    print(f"| {str} |")
    print("-" * (length + 4))

def get_container_id_by_name(container_name):
    client = docker.from_env()
    containers = client.containers.list(filters={"name": container_name})
    if containers:
        return containers[0].id
    return None

def check_that_AWS_vars_exist():
    env_variables = dotenv_values('.env')
    required_variables = ['AWS_ID', 'AWS_KEY', 'AWS_REGION', 'AWS_BUCKET']
    missing_variables = [variable for variable in required_variables if variable not in env_variables]
    if len(missing_variables) == 0:
        return True
    else:
        logger(f"WARNING: You can not download/ulpoad backups to/from bucket becouse this variables missing: {missing_variables}")
        return False
@task
def swarm_exist(c):
    status = c.run("docker info | grep Swarm | sed 's/Swarm: //g'", hide=True).stdout.replace(" ", "").replace("\n", "")
    return status == "active"

@task
def registry_login(c):
    username = os.getenv("GITHUB_USER")
    token = os.getenv("GITHUB_TOKEN")
    c.run(f"docker login -u {username} -p {token} ghcr.io")

@task
def build_master(c):
    c.run(f"docker build -t ghcr.io/{registry}/jenkins-master:latest -f ./docker/Dockerfile_master ./docker")

@task
def build_terraform(c):
    terraform_version = os.getenv("TERRAFORM_VERSION")
    c.run(f"docker build --build-arg TERRAFORM_VERSION={terraform_version} -t ghcr.io/{registry}/jenkins-slave-terraform:latest -f ./docker/Dockerfile_terraform_worker ./docker")

@task
def build_images(c):
    build_master(c)
    build_terraform(c)

@task
def push_master_image(c):
    build_master(c)
    registry_login(c)
    c.run(f"docker push ghcr.io/{registry}/jenkins-master:latest")

@task
def push_terraform_image(c):
    build_terraform(c)
    registry_login(c)
    c.run(f"docker push ghcr.io/{registry}/jenkins-slave-terraform:latest")

@task
def push_images(c):
    push_master_image(c)
    push_terraform_image(c)

@task
def init_swarm(c):
    c.run("docker swarm init --advertise-addr 192.168.0.103")

@task
def start(c):
    registry_login(c)
    if check_that_AWS_vars_exist():
        download_backup = input("Do you want to download backup from AWS bucket? Y/N ")
        if download_backup.lower() == "y":
            c.run(f"python3 ./data.py -i {AWS_ID} -k {AWS_KEY} -r {AWS_REGION} -b {AWS_BUCKET} -f data -m S3_TO_LOCAL")
        
    if not os.path.exists("./data"):
        os.makedirs("./data")
    try:
        c.run(f"docker pull ghcr.io/{registry}/jenkins-master:latest")
    except:
        logger("Error while pull jenkins-master image! Trying to push...")
        push_master_image(c)
    try:
        c.run(f"docker pull ghcr.io/{registry}/jenkins-slave-terraform:latest")
    except:
        logger("Error while pull jenkins-slave-terraform image! Trying to push...")
        push_terraform_image(c)
    if not swarm_exist(c):
        logger("Creating swarm...")
        c.run("docker swarm init --advertise-addr 192.168.0.103")
    c.run(f"REGISTRY={registry} docker stack deploy --compose-file docker-compose.yaml jenkins")

@task
def get_password(c):
    master_container_id = get_container_id_by_name("jenkins-master")
    if master_container_id is None:
         logger("Something goes wrong, it seems like jenkins-master container doesn`t exist...")
    else:
        try:
            first_time_pass = c.run(f"docker exec -t {master_container_id} cat /var/jenkins_home/secrets/initialAdminPassword", hide=True).stdout.replace("\n", "")
            logger(f"First time jenkins password: {first_time_pass}")
        except:
            logger("It seems like first time jenkins password is not available anymore")

@task
def destroy(c):
    try:
        c.run(f"docker swarm leave -f")
    except:
        logger("This node is not part of a swarm")
    delete_volumes = input("Do you want to delete volumes? Y/N ")
    if delete_volumes.lower() == "y":
        c.run("sudo rm -rf ./data")

@task
def create_backup(c):
    if check_that_AWS_vars_exist():
        c.run(f"python3 ./data.py -i {AWS_ID} -k {AWS_KEY} -r {AWS_REGION} -b {AWS_BUCKET} -f data -m LOCAL_TO_S3")
    else:
        logger("It seems like something goes wrong, pls check warnings...")