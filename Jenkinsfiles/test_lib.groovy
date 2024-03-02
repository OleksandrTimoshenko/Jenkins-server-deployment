@Library('Default_jenkins_lib@master') import main.Utilities
lib = new Utilities(this)

pipeline {
    stages {
        stage("Test Lib") {
            steps {
                script {
                    lib.sayHelloFromLib()
                }
            }
        }
    }
}