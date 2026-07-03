# vil.tr — Manual de referencia

Microservicio CRUD de estudiantes (Spring Boot + H2) desplegado con Docker,
Docker Compose y Kubernetes. Este documento es un manual de referencia rápida
para levantar el proyecto, entender su stack, y como plantilla para armar
otro proyecto con la misma arquitectura.

---

## 1. Stack y dependencias necesarias

### 1.1 Qué elegir en Spring Initializr (start.spring.io) para un proyecto nuevo

| Campo | Valor |
|---|---|
| Project | Maven |
| Language | Java |
| Spring Boot | 3.5.x (la última estable de la rama 3.5) |
| Packaging | Jar |
| Java | 17 |

**Dependencies a marcar** (buscar cada una por nombre en el buscador de Initializr):
- **Spring Web** → agrega `spring-boot-starter-web`
- **Spring Data JPA** → agrega `spring-boot-starter-data-jpa`
- **H2 Database** → agrega `com.h2database:h2`
- **Lombok** → agrega `org.projectlombok:lombok`

No hace falta marcar nada más — `spring-boot-starter-test` viene incluido
por default en cualquier proyecto generado por Initializr, sin tener que
buscarlo.

### 1.2 Dependencias resultantes (ver [pom.xml](pom.xml))

| Dependencia | Para qué sirve |
|---|---|
| `spring-boot-starter-web` | Expone endpoints REST (`@RestController`, Tomcat embebido) |
| `spring-boot-starter-data-jpa` | Acceso a datos vía JPA/Hibernate (`JpaRepository`) |
| `com.h2database:h2` (scope `runtime`) | Base de datos temporal en memoria |
| `org.projectlombok:lombok` (`optional`) | Genera getters/setters/constructores (`@Data`, `@Builder`, etc.) |
| `spring-boot-starter-test` (scope `test`) | JUnit + utilidades de testing de Spring Boot |

Requisitos de entorno para correr/compilar:
- Java 17 (`java.version` en `pom.xml`)
- Maven (o el wrapper `mvnw`/`mvnw.cmd` incluido, no hace falta instalarlo aparte)
- Docker Desktop con Kubernetes habilitado (Settings → Kubernetes → Enable Kubernetes)

---

## 2. Estructura del proyecto (arquitectura por capas)

```
com.marilynhackathon.vil.tr/
├── Application.java          → punto de entrada
├── controller/                → capa de presentación (REST)
├── service/                   → lógica de negocio (interfaz)
│   └── impl/                  → implementación de la lógica
├── repository/                → acceso a datos (JpaRepository)
└── model/                     → entidad de dominio (Student)
```
Flujo de dependencia: `Controller → Service → Repository → Model` (nunca al revés).

---

## 3. Cómo funcionan los puertos (mecanismo completo)

Todo el proyecto usa un solo mecanismo de puerto flexible, repetido en cada
capa de infraestructura. Entender esto de una vez evita confusiones en Docker,
Compose, nginx y Kubernetes.

### 3.1 El origen: `${SERVER_PORT:8090}`

En [application.yaml](src/main/resources/application.yaml):
```yaml
server:
  port: ${SERVER_PORT:8090}
```
Esta sintaxis de Spring dice: *"si existe una variable de entorno llamada
`SERVER_PORT`, úsala; si no existe, usa 8090"*. El `8090` es solo un
**fallback**, no un valor fijo — gana siempre la variable de entorno si está
presente, sin importar quién la haya puesto (Dockerfile, docker-compose,
Kubernetes, o tu propia terminal).

### 3.2 Quién define esa variable en cada entorno

| Entorno | Cómo se fija `SERVER_PORT` | Resultado |
|---|---|---|
| Local (`mvnw spring-boot:run`) | Nadie la define | Cae al fallback → **8090** |
| Imagen Docker "pelada" (`docker run`) | `ENV SERVER_PORT=8091` en el [Dockerfile](Dockerfile) | **8091** |
| docker-compose | `environment: SERVER_PORT: 8091` en [docker-compose.yml](docker-compose.yml) | **8091** (sobreescribe el `ENV` de la imagen si difieren) |
| Kubernetes | `env: SERVER_PORT: "8093"` en [k8/marilyn-deployment.yml](k8/marilyn-deployment.yml) | **8093** |

Importante: `EXPOSE 8091` en el Dockerfile es solo **documentación/metadata**
— no fuerza ni restringe en qué puerto escucha realmente la app. Lo único que
decide el puerto real es la variable de entorno que llegue en tiempo de
ejecución.

### 3.3 Los "3 puertos" de un Service de Kubernetes (el más confuso)

En [k8/marilyn-service.yml](k8/marilyn-service.yml) hay tres números de
puerto distintos, cada uno con un rol distinto:

```yaml
ports:
  - port: 80          # puerto del Service DENTRO del clúster
    targetPort: 8093    # puerto real donde escucha el contenedor (= SERVER_PORT del Deployment)
    nodePort: 30093       # puerto público fijo hacia tu máquina (rango 30000-32767)
```
Flujo de una petición: `Cliente externo → nodePort (30093) → port (80) → targetPort (8093, el contenedor)`.

### 3.4 `docker-compose up --build` vs `docker build` manual

`docker build -t vil-tr-app:latest .` construye la imagen sin depender de
Compose. `docker-compose up --build` hace lo mismo, pero solo si el servicio
tiene un bloque `build:` en el yaml (ver [docker-compose.yml](docker-compose.yml)
línea 4-6) — sin eso, Compose intenta **descargar** la imagen en vez de
construirla, y falla si no existe públicamente en Docker Hub.

---

## 4. Cómo funciona nginx (reverse proxy)

En [nginx.conf](nginx.conf):
```nginx
events {
    worker_connections 1024;
}

http {
    server {
        listen 8092;

        location / {
            proxy_pass http://app:8091;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
        }
    }
}
```

- **`listen 8092`** → el puerto donde nginx **recibe** tráfico desde afuera
  (del host / navegador). Debe coincidir con el mapeo `"8092:8092"` de
  `docker-compose.yml`.
- **`proxy_pass http://app:8091`** → el puerto al que nginx **reenvía**
  internamente, dentro de la red Docker (`vil-network`). `app` es el nombre
  del servicio en `docker-compose.yml` (Docker resuelve ese nombre por DNS
  interno); `8091` debe coincidir con el `SERVER_PORT` real de Spring Boot
  dentro de ese contenedor — **no** con el puerto de nginx ni con ningún
  otro puerto de los demás entornos (8090/8093/etc.).
- **`proxy_set_header Host/X-Real-IP`** → reenvía al backend los headers
  originales del cliente (si no se hiciera esto, Spring Boot solo vería la
  IP interna de nginx como si fuera el cliente real).

Cómo se monta el archivo en el contenedor: `docker-compose.yml` tiene
`volumes: - ./nginx.conf:/etc/nginx/nginx.conf:ro` — esto **solo copia el
archivo** dentro del contenedor de nginx, no reemplaza la necesidad de que
el contenido del archivo sea correcto. Si `nginx.conf` tiene un error de
sintaxis (ej. falta un `;`), el contenedor de nginx no arranca en absoluto.

Flujo completo de una petición vía nginx:
```
Cliente → nginx:8092 (listen) → proxy_pass → app:8091 (Spring Boot real) → respuesta
```

---

## 5. Endpoints — `/v1/api/student`

Base URL según el entorno (ver tabla de puertos abajo), ej. `http://localhost:8091`.

### GET `/v1/api/student` — listar todos
```json
[
  {
    "id": 1,
    "dni": "87654321",
    "firstName": "Marilyn",
    "lastName": "Vilcapuma",
    "promotion": 232,
    "date": "2026-07-03T10:15:30"
  }
]
```

### GET `/v1/api/student/{id}` — buscar por id
Respuesta: un objeto `Student` igual al de arriba, o `404` si no existe.

### POST `/v1/api/student` — crear
**Request body:**
```json
{
  "dni": "87654321",
  "firstName": "Marilyn",
  "lastName": "Vilcapuma",
  "promotion": 232
}
```
**Response (201 Created):** el objeto creado, con `id` y `date` generados
por el servidor (`date` se setea automáticamente en `StudentServiceImpl.save()`).

### PUT `/v1/api/student/{id}` — actualizar
**Request body:** igual al de POST. **Response (200 OK):** el objeto actualizado.

### DELETE `/v1/api/student/{id}` — eliminar
**Response:** `204 No Content`.

Todas las operaciones registran logs con las palabras clave
`Invocar` / `Registrar` / `Actualizar` / `Eliminar`
(ver [StudentServiceImpl.java](src/main/java/com/marilynhackathon/vil/tr/service/impl/StudentServiceImpl.java)).

### Tabla de puertos por entorno
| Entorno | Puerto |
|---|---|
| Local (sin Docker) | 8090 |
| Docker / docker-compose (`app`) | 8091 |
| nginx (hacia afuera) | 8092 |
| Kubernetes (dentro del pod) | 8093 |
| Kubernetes NodePort | 30093 |
| Kubernetes port-forward | 8094 |
| MySQL | 3306 |

---

## 6. Comandos Docker

### Construcción y publicación

```powershell
docker build -t vil-tr-app:latest .
```
Construye la imagen usando el [Dockerfile](Dockerfile) multistage (Maven →
JRE mínimo con `jlink` → `scratch`). El `.` es el contexto de build (carpeta
actual); Docker busca ahí un archivo llamado `Dockerfile`.

```powershell
docker login
```
Inicia sesión en Docker Hub (pide usuario y password/token).

```powershell
docker tag vil-tr-app:latest marilynvilcapuma/ht-232-44-marilyn-vilcapuma:latest
```
Le agrega un segundo nombre a la misma imagen local (no la duplica, es un
alias sobre el mismo `IMAGE ID`), con el formato exigido para la entrega.

```powershell
docker push marilynvilcapuma/ht-232-44-marilyn-vilcapuma:latest
```
Sube la imagen a Docker Hub bajo ese nombre.

```powershell
docker run -p 8091:8091 vil-tr-app:latest
```
Corre la imagen de forma aislada (sin Compose), útil para probarla rápido.

### Inspección

```powershell
docker images
```
Lista todas las imágenes descargadas/construidas localmente, con su
`REPOSITORY`, `TAG`, `IMAGE ID` y `SIZE`. Filtra una en particular con
`docker images vil-tr-app`.

```powershell
docker ps
```
Lista los contenedores **actualmente corriendo** (`CONTAINER ID`, imagen,
puertos publicados, nombre).

```powershell
docker ps -a
```
Igual, pero incluye también los contenedores **detenidos** — útil para ver
todo el historial, no solo lo activo.

```powershell
docker port <nombre-o-id-contenedor>
```
Muestra el mapeo real de puertos host↔contenedor de un contenedor
específico — útil para confirmar si un puerto realmente está publicado
hacia tu máquina (lo usamos para diagnosticar el problema de NodePort).

```powershell
docker logs <nombre-o-id-contenedor>
```
Muestra los logs de un contenedor puntual (equivalente a `docker-compose
logs` pero apuntando a un contenedor por su nombre real, no por nombre de
servicio de Compose).

### Limpieza

```powershell
docker stop <nombre-o-id-contenedor>
```
Detiene un contenedor en ejecución (sin borrarlo — sigue existiendo, solo
apagado; aparece en `docker ps -a` pero no en `docker ps`).

```powershell
docker rm <nombre-o-id-contenedor>
```
Elimina un contenedor **detenido** (si está corriendo, primero hay que
`docker stop`, o usar `docker rm -f` para forzar detener+eliminar en un
solo paso). No borra la imagen, solo la instancia del contenedor.

```powershell
docker rmi <nombre-imagen>:<tag>
```
Elimina una **imagen** local (ej. `docker rmi vil-tr-app:latest`). Falla si
hay algún contenedor (aunque esté detenido) todavía usando esa imagen — en
ese caso primero hay que `docker rm` el contenedor.

```powershell
docker system prune
```
Limpieza general: borra contenedores detenidos, redes no usadas, y caché de
build sin usar (pide confirmación antes de borrar). Útil cuando el disco se
llena de capas viejas de builds repetidos.

---

## 7. Comandos Docker Compose

```powershell
docker-compose up --build -d
```
Construye la imagen (según el `build:` de [docker-compose.yml](docker-compose.yml))
y levanta los 3 servicios (`app`, `mysql`, `nginx`) en segundo plano (`-d`).

```powershell
docker-compose up -d
```
Igual que arriba pero sin reconstruir — usa la imagen ya existente (más
rápido si no cambiaste código).

```powershell
docker-compose build
```
Solo construye/reconstruye las imágenes definidas con `build:`, sin levantar
los contenedores todavía — útil para separar el paso de build del de arranque.

```powershell
docker-compose ps
```
Muestra el estado de los 3 contenedores (esperado: los 3 en `Up`).

```powershell
docker-compose logs -f app
```
Sigue en vivo los logs del servicio `app` (`Ctrl+C` para salir). Sin `-f`
muestra el historial completo y termina.

```powershell
docker-compose stop
```
Detiene los 3 contenedores sin eliminarlos (a diferencia de `down`, quedan
creados — un `docker-compose start` posterior los reanuda rápido, sin
recrearlos).

```powershell
docker-compose start
```
Reinicia contenedores previamente detenidos con `stop` (sin reconstruir ni
recrear nada).

```powershell
docker-compose restart
```
Combina `stop` + `start` en un solo comando — útil para reiniciar la app
tras un cambio de configuración externo (sin cambio de imagen).

```powershell
docker-compose down
```
Elimina los contenedores y la red creada, conserva imágenes y volúmenes.

```powershell
docker-compose down -v
```
Igual, pero también borra los volúmenes (ej. datos de MySQL).

```powershell
docker-compose down --rmi all -v
```
Elimina contenedores, red, volúmenes, **y además las imágenes** asociadas a
los servicios — la limpieza más completa.

---

## 8. Comandos Kubernetes

Ver el manual completo y detallado en [k8/COMANDOS.md](k8/COMANDOS.md)
(orden de aplicación, verificación, prueba de endpoints, troubleshooting,
actualización tras un cambio de código, y eliminación).

Resumen mínimo para levantar todo:
```powershell
kubectl apply -f k8/marilyn-namespace.yml
kubectl apply -f k8/marilyn-secret.yml
kubectl apply -f k8/marilyn-deployment.yml
kubectl apply -f k8/marilyn-service.yml
```

Resumen mínimo para eliminar todo:
```powershell
kubectl delete namespace marilyn
```

---

## 9. Checklist de un proyecto nuevo con este mismo stack

Si vas a replicar este proyecto con otra entidad/campos, revisa (en orden):

1. Spring Initializr — mismas dependencias (sección 1.1)
2. `model/`, `repository/`, `service/`, `controller/` — mismo patrón de capas, nuevos campos/entidad
3. `application.yaml` — mismo patrón `${SERVER_PORT:default}`, revisa si necesitas otra BD
4. `Dockerfile` — no suele cambiar (multistage genérico)
5. `docker-compose.yml` — cambia el nombre de imagen y, si aplica, el puerto
6. `nginx.conf` — cambia el `proxy_pass` al nuevo puerto interno de la app
7. `k8/*.yml` — cada campo marcado `REEMPLAZABLE:` en los comentarios de esos archivos
