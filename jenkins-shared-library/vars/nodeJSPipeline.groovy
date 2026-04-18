def call(Map configMap) {
    pipeline {
        agent {
            node {
                label 'AGENT-1'
            }
        }
        environment {
            GIT_URL= "${configMap.GIT_URL}"
            BRANCH= "${configMap.BRANCH}"
            PROJECT='roboshop'
            REGION='us-east-1'
            ACCOUNT_ID='515138251473'
            COMPONENT= "${configMap.COMPONENT}"
            APPVERSION=''
        }
        options {
            timeout(time: 5, unit: 'MINUTES')
            disableConcurrentBuilds()
        }
        stages {
            stage('clean workspace') {
                steps {
                    cleanWs()
                }
            }
            stage('get code') {
                steps {
                    git url: "${GIT_URL}", branch: "${BRANCH}"
                }
            }
            stage('read version') {
                steps {
                    dir("${COMPONENT}") {
                        script {
                            def buildfileversion = readJSON file: 'package.json'
                            env.APPVERSION = buildfileversion.version
                            echo "APPVERSION IS: ${env.APPVERSION}"
                        }
                    }
                }
            }
            stage('build code') {
                steps {
                    dir("${COMPONENT}") {
                        sh "npm install"
                    }
                }
            }
            stage('cqa-sonar') {
                steps {
                    dir("${COMPONENT}") {
                        script {
                            def scannerHome = tool 'sonar-8.0'
                            withSonarQubeEnv('sonar-server') {
                            sh "${scannerHome}/bin/sonar-scanner"
                            }
                        }
                    }
                }
            }
            stage('quality gates') {
                steps {
                    timeout(time:2, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }
            stage('build image') {
                steps {
                    sh "docker build -t ${PROJECT}/${COMPONENT}:${env.APPVERSION}-${BUILD_NUMBER} ./${COMPONENT}"
                }
            }
            stage('scan image') {
                steps {
                    sh "trivy image ${PROJECT}/${COMPONENT}:${env.APPVERSION}-${BUILD_NUMBER} > ${COMPONENT}-${BUILD_NUMBER}-image-scan-report.txt"
                }
            }
            stage('push to ecr') {
                steps {
                    script {
                        withAWS(region:"${REGION}",credentials:'aws-creds') {
                            sh """
                            aws ecr get-login-password --region ${REGION} | docker login --username AWS --password-stdin ${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com
                            docker tag ${PROJECT}/${COMPONENT}:${env.APPVERSION}-${BUILD_NUMBER} ${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${PROJECT}/${COMPONENT}:${env.APPVERSION}-${BUILD_NUMBER}
                            docker push ${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${PROJECT}/${COMPONENT}:${env.APPVERSION}-${BUILD_NUMBER}
                            """
                        }
                    }
                }
            }
        }
    }
}