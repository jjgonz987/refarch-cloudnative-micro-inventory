/*
    To learn how to use this sample pipeline, follow the guide below and enter the
    corresponding values for your environment and for this repository:
    - https://github.com/ibm-cloud-architecture/refarch-cloudnative-devops-kubernetes
*/

// Pod Template
def podLabel = "inventory"
def cloud = env.CLOUD ?: "kubernetes"
def registryCredsID = env.REGISTRY_CREDENTIALS ?: "registry-credentials-id"
def serviceAccount = env.SERVICE_ACCOUNT ?: "jenkins"

// Pod Environment Variables
def namespace = env.NAMESPACE ?: "default"
def registry = env.REGISTRY ?: "docker.io"
def imageName = env.IMAGE_NAME ?: "ibmcase/bluecompute-inventory"
def deploymentLabels = env.DEPLOYMENT_LABELS ?: "app=inventory,tier=backend,version=v1"
def podName = env.POD_NAME ?: "inventory"

// External Test Database Parameters
// For username and passwords
def dbHost = env.DB_HOST
def dbPort = env.DB_PORT ?: "3306"
def dbDatabase = env.DB_DATABASE ?: "inventorydb"
def dbCredsID = env.DB_CREDENTIALS ?: "inventory-mysql-id"

//def dbUser = env.DB_USER
//def dbPassword = env.DB_PASSWORD

podTemplate(label: podLabel, cloud: cloud, serviceAccount: serviceAccount, namespace: namespace, envVars: [
        envVar(key: 'NAMESPACE', value: namespace),
        envVar(key: 'REGISTRY', value: registry),
        envVar(key: 'IMAGE_NAME', value: imageName),
        envVar(key: 'DEPLOYMENT_LABELS', value: deploymentLabels),
        envVar(key: 'POD_NAME', value: podName),
        envVar(key: 'DB_HOST', value: dbHost),
        envVar(key: 'DB_PORT', value: dbPort),
        envVar(key: 'DB_DATABASE', value: dbDatabase)
    ],
    volumes: [
        hostPathVolume(hostPath: '/etc/docker/certs.d', mountPath: '/etc/docker/certs.d'),
        hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')
    ],
    containers: [
        containerTemplate(name: 'jdk', image: 'ibmcase/openjdk-bash:latest', ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl', ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'docker' , image: 'ibmcase/docker-bash:latest', ttyEnabled: true, command: 'cat')
  ]) {

    node(podLabel) {
        checkout scm
        container(name:'jdk', shell:'/bin/bash') {
            stage('Local - Build and Unit Test') {
                sh """
                #!/bin/bash

                ./gradlew build
                """
            }
            stage('Local - Run and Test') {
                sh """
                #!/bin/bash

                JAVA_OPTS="-Dspring.datasource.url=jdbc:mysql://${env.DB_HOST}:${env.DB_PORT}/${env.DB_DATABASE}"
                JAVA_OPTS="\${JAVA_OPTS} -Dspring.datasource.port=${env.DB_PORT}"

                java \${JAVA_OPTS} -jar build/libs/micro-inventory-0.0.1.jar &

                sleep 25

                set +x
                bash scripts/api_tests.sh
                set -x
                """
            }
        }
        container(name:'docker', shell:'/bin/bash') {
            stage('Docker - Build Image') {
                sh """
                #!/bin/bash

                # Get image
                if [ "${env.REGISTRY}" = "docker.io" ]; then
                    IMAGE=${env.IMAGE_NAME}:${env.BUILD_NUMBER}
                else
                    IMAGE=${env.REGISTRY}/${env.NAMESPACE}/${env.IMAGE_NAME}:${env.BUILD_NUMBER}
                fi

                docker build -t \${IMAGE} .
                """
            }
            stage('Docker - Run and Test') {
                sh """
                #!/bin/bash

                # Get image
                if [ "${env.REGISTRY}" = "docker.io" ]; then
                    IMAGE=${env.IMAGE_NAME}:${env.BUILD_NUMBER}
                else
                    IMAGE=${env.REGISTRY}/${env.NAMESPACE}/${env.IMAGE_NAME}:${env.BUILD_NUMBER}
                fi

                # Kill Container if it already exists
                docker kill ${env.POD_NAME} || true
                docker rm ${env.POD_NAME} || true

                # Start Container
                docker run --name ${env.POD_NAME} -d -p 8080:8080 \
                    -e MYSQL_HOST=${env.DB_HOST} \
                    -e MYSQL_PORT=${env.DB_PORT} \
                    -e MYSQL_USER=${MYSQL_USER} \
                    -e MYSQL_PASSWORD=${MYSQL_PASSWORD} \
                    -e MYSQL_DATABASE=${env.DB_DATABASE} \${IMAGE}

                # Let the container start
                sleep 25

                # Check that container started successfully
                docker ps

                # Check the logs
                docker logs ${env.POD_NAME}

                # Run tests
                set +x
                ./scripts/api_tests.sh
                set -x
                """
            }
            stage('Docker - Push Image to Registry') {
                withCredentials([usernamePassword(credentialsId: registryCredsID,
                                               usernameVariable: 'USERNAME',
                                               passwordVariable: 'PASSWORD')]) {
                    sh """
                    #!/bin/bash

                    # Get image
                    if [ "${env.REGISTRY}" = "docker.io" ]; then
                        IMAGE=${env.IMAGE_NAME}:${env.BUILD_NUMBER}
                    else
                        IMAGE=${env.REGISTRY}/${env.NAMESPACE}/${env.IMAGE_NAME}:${env.BUILD_NUMBER}
                    fi

                    docker login -u ${USERNAME} -p ${PASSWORD} ${env.REGISTRY}

                    docker push \${IMAGE}
                    """
                }
            }
        }
        container(name:'kubectl') {
            stage('Kubernetes - Deploy new Docker Image') {
                sh """
                #!/bin/bash

                DEPLOYMENT=`kubectl --namespace=${env.NAMESPACE} get deployments -l ${env.DEPLOYMENT_LABELS} -o name`
                kubectl --namespace=${env.NAMESPACE} get \${DEPLOYMENT}
                if [ \${?} -ne "0" ]; then
                    # No deployment to update
                    echo 'No deployment to update'
                    exit 1
                fi

                # Get image
                if [ "${env.REGISTRY}" = "docker.io" ]; then
                    IMAGE=${env.IMAGE_NAME}:${env.BUILD_NUMBER}
                else
                    IMAGE=${env.REGISTRY}/${env.NAMESPACE}/${env.IMAGE_NAME}:${env.BUILD_NUMBER}
                fi

                # Update deployment and check rollout status
                kubectl --namespace=${env.NAMESPACE} set image \${DEPLOYMENT} ${env.POD_NAME}=\${IMAGE}
                kubectl --namespace=${env.NAMESPACE} rollout status \${DEPLOYMENT}
                """
            }
        }
    }
}