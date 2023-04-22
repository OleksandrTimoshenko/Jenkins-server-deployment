# Docker setup for jenkins with workers

### Start master Jenkins
```
mkdir data
cp .env.example .env
docker swarm init
docker stack deploy --compose-file docker-compose.yaml jenkins
docker service ls
```

### Get credentials for first setup
```
docker exec -t <master_id> cat /var/jenkins_home/secrets/initialAdminPassword
```

### Set correct .env variables in your [Jenkins](http://127.0.0.1)
![image info](./pictures/setup-credentials.png)

### Increase number of workers
```
docker service scale jenkins_jenkins-worker=5
```

### Remove stack
```
docker stack rm jenkins
docker swarm leave -f
```

### TODO:
- add make(invoke, bash) scripts for first setup
- add script for backup ***./data*** folder (S3)
- add Dockerfiles for setup different types of workers
