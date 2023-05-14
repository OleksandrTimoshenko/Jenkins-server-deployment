pipeline {
    agent {
        node {
            label 'master'
        }
    }
  
  stages {
    stage('Execute Bash Command') {
      steps {
        sh "docker service scale ${WORKER_NAME}=${SCALE_TO}"
      }
    }
  }
}