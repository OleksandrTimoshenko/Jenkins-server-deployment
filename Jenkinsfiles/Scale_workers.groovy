@Library('Default_jenkins_lib@master') import main.Utilities
lib = new Utilities(this)

pipeline {
    agent {
        node {
            label 'master'
        }
    }
    stages {
        stage('Scale Docker Service') {
            steps {
                script {
                    lib.scaleJenkinsWorkers(params.CONTAINER_NAME, params.WORKERS_NUMBER)
                }
            }
        }
    }
}