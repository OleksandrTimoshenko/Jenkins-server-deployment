# Docker setup for jenkins with workers

### Start master Jenkins
```
mkdir data
cp .env.example .env
docker swarm init --advertise-addr X.X.X.X
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

### Upload volume to S3
```
python3 ./data.py -i <AWS_ID> -k <AWS_KEY> -r <AWS_REGION> -b <AWS _S3_BUCKET> -f data -m S3_TO_LOCAL
```

### Download volume from S3
```
python3 ./data.py -i <AWS_ID> -k <AWS_KEY> -r <AWS_REGION> -b <AWS _S3_BUCKET> -f data -m LOCAL_TO_S3
```

### TODO:
- add make(invoke, bash) scripts for first setup
- add Dockerfiles for setup different types of workers (Terraform - done)
