# API Gateway - Sistema de Reservas de Hoteles

API Gateway basado en **Spring Cloud Gateway**. Actúa como punto de entrada único para todos los microservicios, gestionando enrutamiento, CORS y balanceo de carga.

## Información del Servicio

| Propiedad | Valor |
|-----------|-------|
| Puerto | 8080 |
| Java | 21 |
| Spring Boot | 3.4.0 |
| Spring Cloud | 2024.0.1 |
| Tipo | Reactive (WebFlux) |

## Estructura del Proyecto

```
api-gateway/
├── pom.xml
└── src/main/
    ├── java/com/hotel/gateway/
    │   ├── ApiGatewayApplication.java
    │   └── config/
    └── resources/
        └── application.yml
```

## Rutas Configuradas

### Auth Service (8081)

| Ruta | Destino |
|------|---------|
| `/api/v1/auth/**` | auth-service |
| `/api/v1/users/**` | auth-service |

### Hotel Service (8082)

| Ruta | Destino |
|------|---------|
| `/api/v1/hoteles/**` | hotel-service |
| `/api/v1/departamentos/**` | hotel-service |
| `/api/v1/habitaciones/**` | hotel-service |
| `/api/v1/tipos-habitacion/**` | hotel-service |

### Reserva Service (8083)

| Ruta | Destino |
|------|---------|
| `/api/v1/reservas/**` | reserva-service |
| `/api/v1/mis-reservas/**` | reserva-service |
| `/api/v1/clientes/**` | reserva-service |
| `/api/v1/dashboard/**` | reserva-service |
| `/api/v1/admin/**` | reserva-service |

### Notificacion Service (8084)

| Ruta | Destino |
|------|---------|
| `/api/v1/notificaciones/**` | notificacion-service |
| `/api/v1/mis-notificaciones/**` | notificacion-service |
| `/api/v1/plantillas/**` | notificacion-service |
| `/api/v1/contacto/**` | notificacion-service |

## Variables de Entorno

| Variable | Descripción | Default |
|----------|-------------|---------|
| `SERVER_PORT` | Puerto del gateway | `8080` |
| `CONFIG_SERVER_URL` | URL Config Server | `http://localhost:8888` |
| `EUREKA_URL` | URL Eureka | `http://localhost:8761/eureka` |
| `CORS_ALLOWED_ORIGINS` | Orígenes CORS | `http://localhost:4200` |
| `AUTH_SERVICE_URL` | URL Auth Service | `http://auth-service:8081` |
| `HOTEL_SERVICE_URL` | URL Hotel Service | `http://hotel-service:8082` |
| `RESERVA_SERVICE_URL` | URL Reserva Service | `http://reserva-service:8083` |
| `NOTIFICACION_SERVICE_URL` | URL Notificacion Service | `http://notificacion-service:8084` |

## Endpoints Actuator

```bash
# Health check
GET http://localhost:8080/actuator/health

# Información
GET http://localhost:8080/actuator/info

# Rutas del gateway
GET http://localhost:8080/actuator/gateway/routes
```

---

## Docker

### Dockerfile

```dockerfile
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
COPY --from=builder /app/target/*.jar app.jar
USER spring:spring
EXPOSE 8080
ENV JAVA_OPTS="-Xms256m -Xmx512m"
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

### docker-compose.yml

```yaml
version: '3.8'

services:
  api-gateway:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: api-gateway
    ports:
      - "8080:8080"
    environment:
      - SERVER_PORT=8080
      - CONFIG_SERVER_URL=http://config-server:8888
      - EUREKA_URL=http://discovery-service:8761/eureka
      - CORS_ALLOWED_ORIGINS=http://localhost:4200
      - JAVA_OPTS=-Xms256m -Xmx512m
      - JAVA_OPTS=-Xms256m -Xmx512m
      # URLs de microservicios (para modo sin Eureka)
      - AUTH_SERVICE_URL=http://auth-service:8081
      - HOTEL_SERVICE_URL=http://hotel-service:8082
      - RESERVA_SERVICE_URL=http://reserva-service:8083
      - NOTIFICACION_SERVICE_URL=http://notificacion-service:8084
    depends_on:
      config-server:
        condition: service_healthy
      discovery-service:
        condition: service_healthy
    networks:
      - hotel-network
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s
    restart: unless-stopped

networks:
  hotel-network:
    external: true
```

### docker-compose.yml (Completo con todos los servicios)

```yaml
version: '3.8'

services:
  # ============================================
  # INFRAESTRUCTURA
  # ============================================

  mysql:
    image: mysql:8.0
    container_name: mysql-hotel
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_USER: hotel_user
      MYSQL_PASSWORD: hotel_pass
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
      - ./init-db.sql:/docker-entrypoint-initdb.d/init-db.sql
    networks:
      - hotel-network
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5

  rabbitmq:
    image: rabbitmq:3-management
    container_name: rabbitmq-hotel
    ports:
      - "5672:5672"
      - "15672:15672"
    networks:
      - hotel-network
    healthcheck:
      test: rabbitmq-diagnostics -q ping
      interval: 10s
      timeout: 5s
      retries: 5

  zookeeper:
    image: confluentinc/cp-zookeeper:7.4.0
    container_name: zookeeper-hotel
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports:
      - "2181:2181"
    networks:
      - hotel-network

  kafka:
    image: confluentinc/cp-kafka:7.4.0
    container_name: kafka-hotel
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    networks:
      - hotel-network

  # ============================================
  # CONFIG & DISCOVERY
  # ============================================

  config-server:
    build: ../config-server
    container_name: config-server
    ports:
      - "8888:8888"
    environment:
      - CONFIG_REPO_URI=https://github.com/tu-usuario/config-repo.git
    networks:
      - hotel-network
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8888/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

  discovery-service:
    build: ../discovery-service
    container_name: discovery-service
    ports:
      - "8761:8761"
    depends_on:
      config-server:
        condition: service_healthy
    networks:
      - hotel-network
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8761/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

  # ============================================
  # API GATEWAY
  # ============================================

  api-gateway:
    build: .
    container_name: api-gateway
    ports:
      - "8080:8080"
    environment:
      - SERVER_PORT=8080
      - CONFIG_SERVER_URL=http://config-server:8888
      - EUREKA_URL=http://discovery-service:8761/eureka
      - CORS_ALLOWED_ORIGINS=http://localhost:4200
    depends_on:
      discovery-service:
        condition: service_healthy
    networks:
      - hotel-network
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 60s

  # ============================================
  # MICROSERVICIOS
  # ============================================

  auth-service:
    build: ../ms-auth/auth-service
    container_name: auth-service
    ports:
      - "8081:8081"
    environment:
      - SERVER_PORT=8081
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/auth_db?useSSL=false&allowPublicKeyRetrieval=true
      - SPRING_DATASOURCE_USERNAME=hotel_user
      - SPRING_DATASOURCE_PASSWORD=hotel_pass
      - SPRING_RABBITMQ_HOST=rabbitmq
      - JWT_SECRET_KEY=tu-clave-secreta-256-bits
      - EUREKA_URL=http://discovery-service:8761/eureka
    depends_on:
      mysql:
        condition: service_healthy
      discovery-service:
        condition: service_healthy
    networks:
      - hotel-network

  hotel-service:
    build: ../ms-hotel/hotel-service
    container_name: hotel-service
    ports:
      - "8082:8082"
    environment:
      - SERVER_PORT=8082
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/hotel_db?useSSL=false&allowPublicKeyRetrieval=true
      - SPRING_DATASOURCE_USERNAME=hotel_user
      - SPRING_DATASOURCE_PASSWORD=hotel_pass
      - AUTH_SERVICE_URL=http://auth-service:8081
      - EUREKA_URL=http://discovery-service:8761/eureka
    depends_on:
      - auth-service
    networks:
      - hotel-network

  reserva-service:
    build: ../ms-reserva/reserva-service
    container_name: reserva-service
    ports:
      - "8083:8083"
    environment:
      - SERVER_PORT=8083
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/reserva_db?useSSL=false&allowPublicKeyRetrieval=true
      - SPRING_DATASOURCE_USERNAME=hotel_user
      - SPRING_DATASOURCE_PASSWORD=hotel_pass
      - KAFKA_BOOTSTRAP_SERVERS=kafka:29092
      - AUTH_SERVICE_URL=http://auth-service:8081
      - HOTEL_SERVICE_URL=http://hotel-service:8082
      - EUREKA_URL=http://discovery-service:8761/eureka
    depends_on:
      - hotel-service
      - kafka
    networks:
      - hotel-network

  notificacion-service:
    build: ../ms-notificacion/notificacion-service
    container_name: notificacion-service
    ports:
      - "8084:8084"
    environment:
      - SERVER_PORT=8084
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/notificacion_db?useSSL=false&allowPublicKeyRetrieval=true
      - SPRING_DATASOURCE_USERNAME=hotel_user
      - SPRING_DATASOURCE_PASSWORD=hotel_pass
      - KAFKA_BOOTSTRAP_SERVERS=kafka:29092
      - SPRING_RABBITMQ_HOST=rabbitmq
      - SPRING_MAIL_USERNAME=${MAIL_USERNAME}
      - SPRING_MAIL_PASSWORD=${MAIL_PASSWORD}
      - AUTH_SERVICE_URL=http://auth-service:8081
      - EUREKA_URL=http://discovery-service:8761/eureka
    depends_on:
      - reserva-service
    networks:
      - hotel-network

networks:
  hotel-network:
    driver: bridge

volumes:
  mysql_data:
```

### Comandos Docker

```bash
# Compilar
mvn clean package -DskipTests

# Construir imagen
docker build -t api-gateway:latest .

# Crear red
docker network create hotel-network

# Ejecutar
docker run -d \
  --name api-gateway \
  -p 8080:8080 \
  -e SERVER_PORT=8080 \
  -e EUREKA_URL=http://discovery-service:8761/eureka \
  -e CORS_ALLOWED_ORIGINS=http://localhost:4200 \
  --network hotel-network \
  api-gateway:latest

# Verificar
curl http://localhost:8080/actuator/health

# Ver rutas
curl http://localhost:8080/actuator/gateway/routes

# Logs
docker logs -f api-gateway

# Detener
docker stop api-gateway && docker rm api-gateway
```

---

## Kubernetes

### Deployment

```yaml
# k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api-gateway
  namespace: hotel-system
  labels:
    app: api-gateway
spec:
  replicas: 2
  selector:
    matchLabels:
      app: api-gateway
  template:
    metadata:
      labels:
        app: api-gateway
    spec:
      containers:
        - name: api-gateway
          image: ${ACR_NAME}.azurecr.io/api-gateway:latest
          ports:
            - containerPort: 8080
          env:
            - name: SERVER_PORT
              value: "8080"
            - name: CONFIG_SERVER_URL
              value: "http://config-server:8888"
            - name: EUREKA_URL
              value: "http://discovery-service:8761/eureka"
            - name: CORS_ALLOWED_ORIGINS
              value: "https://tu-dominio.com,http://localhost:4200"
          resources:
            requests:
              memory: "256Mi"
              cpu: "100m"
            limits:
              memory: "512Mi"
              cpu: "500m"
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 5
            timeoutSeconds: 3
            failureThreshold: 3
          startupProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 30
```

### Service

```yaml
# k8s/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: api-gateway
  namespace: hotel-system
  labels:
    app: api-gateway
spec:
  type: LoadBalancer
  selector:
    app: api-gateway
  ports:
    - port: 80
      targetPort: 8080
      protocol: TCP
      name: http
```

### Ingress

```yaml
# k8s/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: api-gateway-ingress
  namespace: hotel-system
  annotations:
    kubernetes.io/ingress.class: nginx
    cert-manager.io/cluster-issuer: letsencrypt-prod
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    nginx.ingress.kubernetes.io/proxy-body-size: "10m"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "60"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "60"
spec:
  tls:
    - hosts:
        - api.tu-dominio.com
      secretName: api-gateway-tls
  rules:
    - host: api.tu-dominio.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: api-gateway
                port:
                  number: 80
```

### ConfigMap

```yaml
# k8s/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: api-gateway-config
  namespace: hotel-system
data:
  CORS_ALLOWED_ORIGINS: "https://tu-dominio.com,https://www.tu-dominio.com"
  LOG_LEVEL: "INFO"
```

### HorizontalPodAutoscaler

```yaml
# k8s/hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: api-gateway-hpa
  namespace: hotel-system
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: api-gateway
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

### Comandos Kubernetes

```bash
# Crear namespace
kubectl create namespace hotel-system

# Aplicar manifiestos
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/ingress.yaml
kubectl apply -f k8s/hpa.yaml

# Verificar
kubectl get pods -n hotel-system -l app=api-gateway
kubectl get svc -n hotel-system -l app=api-gateway
kubectl get ingress -n hotel-system

# Ver logs
kubectl logs -f deployment/api-gateway -n hotel-system

# Port-forward para testing local
kubectl port-forward svc/api-gateway 8080:80 -n hotel-system

# Ver rutas del gateway
curl http://localhost:8080/actuator/gateway/routes

# Escalar manualmente
kubectl scale deployment api-gateway --replicas=3 -n hotel-system

# Ver HPA status
kubectl get hpa api-gateway-hpa -n hotel-system
```

---

## Azure

### 1. Variables de Entorno

```bash
export RESOURCE_GROUP="rg-hotel-reservas"
export LOCATION="eastus"
export ACR_NAME="acrhotelreservas"
export AKS_CLUSTER="aks-hotel-reservas"
```

### 2. Construir y Subir a ACR

```bash
# Login en ACR
az acr login --name $ACR_NAME

# Build local y push
mvn clean package -DskipTests
docker build -t $ACR_NAME.azurecr.io/api-gateway:v1.0.0 .
docker push $ACR_NAME.azurecr.io/api-gateway:v1.0.0

# O build en ACR (recomendado)
az acr build \
  --registry $ACR_NAME \
  --image api-gateway:v1.0.0 \
  --image api-gateway:latest \
  .

# Verificar
az acr repository show-tags \
  --name $ACR_NAME \
  --repository api-gateway \
  --output table
```

### 3. Deployment en AKS

```yaml
# k8s/azure-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api-gateway
  namespace: hotel-system
spec:
  replicas: 2
  selector:
    matchLabels:
      app: api-gateway
  template:
    metadata:
      labels:
        app: api-gateway
    spec:
      containers:
        - name: api-gateway
          image: acrhotelreservas.azurecr.io/api-gateway:v1.0.0
          ports:
            - containerPort: 8080
          env:
            - name: SERVER_PORT
              value: "8080"
            - name: CONFIG_SERVER_URL
              value: "http://config-server:8888"
            - name: EUREKA_URL
              value: "http://discovery-service:8761/eureka"
            - name: CORS_ALLOWED_ORIGINS
              valueFrom:
                configMapKeyRef:
                  name: api-gateway-config
                  key: CORS_ALLOWED_ORIGINS
          resources:
            requests:
              memory: "256Mi"
              cpu: "100m"
            limits:
              memory: "512Mi"
              cpu: "500m"
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 5
```

### 4. Azure Application Gateway (Alternativa a Ingress)

```bash
# Crear Application Gateway
az network application-gateway create \
  --name appgw-hotel-reservas \
  --resource-group $RESOURCE_GROUP \
  --location $LOCATION \
  --sku Standard_v2 \
  --capacity 2 \
  --frontend-port 80 \
  --http-settings-port 8080 \
  --http-settings-protocol Http \
  --routing-rule-type Basic

# Habilitar AGIC en AKS
az aks enable-addons \
  --resource-group $RESOURCE_GROUP \
  --name $AKS_CLUSTER \
  --addons ingress-appgw \
  --appgw-id $(az network application-gateway show -n appgw-hotel-reservas -g $RESOURCE_GROUP --query id -o tsv)
```

### 5. Azure DevOps Pipeline

```yaml
# azure-pipelines.yml
trigger:
  branches:
    include:
      - main
  paths:
    include:
      - api-gateway/**

variables:
  dockerRegistryServiceConnection: 'acr-connection'
  imageRepository: 'api-gateway'
  containerRegistry: 'acrhotelreservas.azurecr.io'
  dockerfilePath: 'api-gateway/Dockerfile'
  tag: '$(Build.BuildId)'

pool:
  vmImage: 'ubuntu-latest'

stages:
  - stage: Build
    displayName: 'Build and Push'
    jobs:
      - job: Build
        steps:
          - task: Maven@3
            displayName: 'Maven Package'
            inputs:
              mavenPomFile: 'api-gateway/pom.xml'
              goals: 'clean package'
              options: '-DskipTests'
              javaHomeOption: 'JDKVersion'
              jdkVersionOption: '1.21'

          - task: Docker@2
            displayName: 'Build and Push Image'
            inputs:
              command: buildAndPush
              repository: $(imageRepository)
              dockerfile: $(dockerfilePath)
              containerRegistry: $(dockerRegistryServiceConnection)
              tags: |
                $(tag)
                latest

  - stage: Deploy
    displayName: 'Deploy to AKS'
    dependsOn: Build
    jobs:
      - deployment: Deploy
        environment: 'production'
        strategy:
          runOnce:
            deploy:
              steps:
                - task: KubernetesManifest@0
                  displayName: 'Deploy to Kubernetes'
                  inputs:
                    action: deploy
                    kubernetesServiceConnection: 'aks-connection'
                    namespace: hotel-system
                    manifests: |
                      api-gateway/k8s/deployment.yaml
                      api-gateway/k8s/service.yaml
                      api-gateway/k8s/ingress.yaml
                    containers: |
                      $(containerRegistry)/$(imageRepository):$(tag)
```

### 6. Desplegar

```bash
# Obtener credenciales AKS
az aks get-credentials --resource-group $RESOURCE_GROUP --name $AKS_CLUSTER

# Crear ConfigMap
kubectl create configmap api-gateway-config \
  --namespace hotel-system \
  --from-literal=CORS_ALLOWED_ORIGINS="https://tu-dominio.com"

# Aplicar manifiestos
kubectl apply -f k8s/azure-deployment.yaml
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/ingress.yaml

# Verificar
kubectl get pods -n hotel-system -l app=api-gateway
kubectl get svc -n hotel-system api-gateway

# Obtener IP externa
kubectl get svc api-gateway -n hotel-system -o jsonpath='{.status.loadBalancer.ingress[0].ip}'
```

---

## Arquitectura

```
                                    ┌─────────────────────────────────────┐
                                    │           INTERNET                   │
                                    └──────────────┬──────────────────────┘
                                                   │
                                    ┌──────────────▼──────────────────────┐
                                    │     Azure Application Gateway       │
                                    │     o NGINX Ingress Controller      │
                                    └──────────────┬──────────────────────┘
                                                   │
                                    ┌──────────────▼──────────────────────┐
                                    │         API GATEWAY (:8080)         │
                                    │      Spring Cloud Gateway           │
                                    │                                     │
                                    │  ┌─────────────────────────────┐   │
                                    │  │        CORS Filter          │   │
                                    │  │   (localhost:4200, etc)     │   │
                                    │  └─────────────────────────────┘   │
                                    │                                     │
                                    │  ┌─────────────────────────────┐   │
                                    │  │      Route Definitions       │   │
                                    │  │   /api/v1/auth/** → :8081   │   │
                                    │  │   /api/v1/hoteles/** → :8082│   │
                                    │  │   /api/v1/reservas/** → :8083│  │
                                    │  │   /api/v1/notificaciones/**  │  │
                                    │  │              → :8084         │   │
                                    │  └─────────────────────────────┘   │
                                    └──────────────┬──────────────────────┘
                                                   │
                    ┌──────────────────────────────┼──────────────────────────────┐
                    │                              │                              │
         ┌──────────▼──────────┐      ┌───────────▼───────────┐      ┌──────────▼──────────┐
         │   Auth Service      │      │    Hotel Service      │      │  Reserva Service    │
         │      :8081          │      │       :8082           │      │      :8083          │
         └─────────────────────┘      └───────────────────────┘      └─────────────────────┘
                                                                               │
                                                                    ┌──────────▼──────────┐
                                                                    │ Notificacion Service│
                                                                    │       :8084         │
                                                                    └─────────────────────┘
```

---

## Configuración CORS

El gateway configura CORS globalmente para el frontend Angular:

```yaml
spring:
  cloud:
    gateway:
      globalcors:
        add-to-simple-url-handler-mapping: true
        corsConfigurations:
          '[/**]':
            allowedOrigins:
              - "http://localhost:4200"
              - "${CORS_ALLOWED_ORIGINS}"
            allowedMethods:
              - GET
              - POST
              - PUT
              - PATCH
              - DELETE
              - OPTIONS
            allowedHeaders:
              - "*"
            exposedHeaders:
              - "Authorization"
              - "Content-Type"
            allowCredentials: true
            maxAge: 3600
```

---

## Troubleshooting

### Ver rutas configuradas

```bash
curl http://localhost:8080/actuator/gateway/routes | jq
```

### Verificar conectividad con microservicios

```bash
# Desde el pod del gateway
kubectl exec -it deployment/api-gateway -n hotel-system -- sh

# Probar conexión a cada servicio
wget -qO- http://auth-service:8081/api/v1/actuator/health
wget -qO- http://hotel-service:8082/actuator/health
wget -qO- http://reserva-service:8083/api/v1/actuator/health
wget -qO- http://notificacion-service:8084/actuator/health
```

### Logs de debugging

```bash
# Ver logs con nivel DEBUG
kubectl logs -f deployment/api-gateway -n hotel-system | grep -E "(route|forward|error)"

# Habilitar logs detallados
kubectl set env deployment/api-gateway -n hotel-system \
  LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_CLOUD_GATEWAY=DEBUG
```

### Problemas comunes

**1. 503 Service Unavailable**
```bash
# Verificar que los servicios backend estén corriendo
kubectl get pods -n hotel-system
kubectl describe pod <pod-name> -n hotel-system
```

**2. CORS errors**
```bash
# Verificar configuración CORS
curl -I -X OPTIONS http://localhost:8080/api/v1/hoteles \
  -H "Origin: http://localhost:4200" \
  -H "Access-Control-Request-Method: GET"
```

**3. Gateway timeout**
```yaml
# Aumentar timeouts en application.yml
spring:
  cloud:
    gateway:
      httpclient:
        connect-timeout: 10000
        response-timeout: 30s
```

---

## Ejecución Local

```bash
# Compilar
mvn clean package -DskipTests

# Ejecutar
java -jar target/api-gateway-1.0.0-SNAPSHOT.jar

# Con variables de entorno
java -jar target/api-gateway-1.0.0-SNAPSHOT.jar \
  --server.port=8080 \
  --eureka.client.service-url.defaultZone=http://localhost:8761/eureka

# O con Maven
mvn spring-boot:run

# Verificar
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/gateway/routes
```

---

## Orden de Inicio

El API Gateway debe iniciar **después** de Config Server y Discovery Service:

```
1. Config Server (8888)       ← Primero
2. Discovery Service (8761)   ← Segundo
3. API Gateway (8080)         ← Tercero
4. Microservicios (8081-8084) ← Después
```
