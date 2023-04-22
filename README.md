# Docker setup for jenkins with workers

### Start master Jenkins
mkdir data
cp .env.example .env
docker-compose up nginx jenkins-master

### Set correct .env variables in your [Jenkins](http://127.0.0.1)
![image info](./pictures/setup-credentials.png)

### Restart containers
docker-compose down && docker-compose up

### TODO:
- add make(invoke, bash) file for first setup
- add script for backup *./data* folder (S3)
- configure for docker swarm