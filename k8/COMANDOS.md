# Comandos de Kubernetes — vil.tr

Manual de referencia para desplegar, probar, depurar y eliminar el microservicio
en el clúster de Kubernetes de Docker Desktop. Todos los comandos asumen que
estás parado dentro de la carpeta `vil.tr/` (así `k8/archivo.yml` resuelve bien).

---

## 0. Prerrequisitos

```powershell
kubectl cluster-info
```
Confirma que el clúster está activo. Si falla: Docker Desktop → Settings →
Kubernetes → Enable Kubernetes, y espera a que el ícono quede verde.

---

## 1. Aplicar los manifiestos (ORDEN OBLIGATORIO)

El orden importa porque cada archivo depende del anterior:
`Namespace` → `Secret` → `Deployment` → `Service`.

```powershell
kubectl apply -f k8/marilyn-namespace.yml
```
Crea el namespace `marilyn` — el espacio de trabajo donde vivirán todos los
demás recursos. Debe ir primero porque los otros 3 archivos lo referencian
en su `metadata.namespace`.

```powershell
kubectl apply -f k8/marilyn-secret.yml
```
Crea el Secret `marilyn-db-secret` con las credenciales de la base de datos
(usuario/password en base64). Debe ir antes que el Deployment porque este
lo referencia vía `secretKeyRef` — si no existe, los pods quedan en
`CreateContainerConfigError`.

```powershell
kubectl apply -f k8/marilyn-deployment.yml
```
Crea el Deployment con 3 réplicas del microservicio, usando la imagen de
Docker Hub y leyendo `SERVER_PORT`, `DATABASE_USERNAME` y `DATABASE_PASSWORD`
como variables de entorno.

```powershell
kubectl apply -f k8/marilyn-service.yml
```
Crea el Service tipo `NodePort` que expone los 3 pods del Deployment hacia
afuera (puerto 80 → targetPort 8093 → nodePort 30093).

### Resumen rápido — los 4 comandos, en orden, sin explicación (para copiar y pegar)
```powershell
kubectl apply -f k8/marilyn-namespace.yml
kubectl apply -f k8/marilyn-secret.yml
kubectl apply -f k8/marilyn-deployment.yml
kubectl apply -f k8/marilyn-service.yml
```

---

## 2. Verificar el estado

```powershell
kubectl get pods -n marilyn
```
Lista los pods y su estado. Esperado: 3 pods en `Running`, `READY: 1/1`.

```powershell
kubectl get svc -n marilyn
```
Muestra el Service y su mapeo de puertos. Esperado: `TYPE: NodePort`,
`PORT(S): 80:30093/TCP`.

```powershell
kubectl get endpoints -n marilyn
```
Confirma que el Service SÍ encontró pods (por el `selector: app: vil-tr-app`).
Si sale vacío, el Service no tiene a quién enviar tráfico — revisa que los
labels de `marilyn-deployment.yml` y `marilyn-service.yml` coincidan.

```powershell
kubectl get secrets -n marilyn
```
Confirma que el Secret existe (sin mostrar sus valores).

```powershell
kubectl get all -n marilyn
```
Vista resumida de TODO lo que hay en el namespace (pods, services,
deployments, replicasets) en un solo comando — útil para un vistazo rápido.

---

## 3. Probar los endpoints (los 2 accesos que pide la rúbrica)

### 3a. Vía NodePort (acceso permanente, sin comandos extra)
```powershell
Invoke-RestMethod -Uri "http://localhost:30093/v1/api/student" -Method Get
```
O directo en el navegador: `http://localhost:30093/v1/api/student`

### 3b. Vía port-forward (el "puente", acceso temporal)
```powershell
kubectl port-forward -n marilyn svc/marilyn-service 8094:80
```
Este comando se queda corriendo (bloquea la terminal) — ábrelo en una
terminal aparte. Formato: `<puerto-local>:<port-del-service>`.

En otra terminal o en el navegador, mientras el comando de arriba sigue activo:
```powershell
Invoke-RestMethod -Uri "http://localhost:8094/v1/api/student" -Method Get
```
Para cortar el túnel: `Ctrl+C` en la terminal donde corre el port-forward.

---

## 4. Depuración / troubleshooting

```powershell
kubectl describe pod -n marilyn -l app=vil-tr-app
```
Detalle completo de los pods con ese label — incluye la sección `Events`
al final, la más útil para ver errores (pull de imagen fallido, secret no
encontrado, crash del contenedor, etc.).

```powershell
kubectl logs -n marilyn -l app=vil-tr-app --tail 50
```
Muestra los últimos 50 logs de todos los pods con ese label — confirma si
Spring Boot arrancó bien (`Started Application in X seconds`) o si crasheó.

```powershell
kubectl logs -n marilyn -l app=vil-tr-app -f
```
Igual que arriba, pero en modo "seguir en vivo" (`-f` de *follow*) — útil
mientras haces pruebas y quieres ver los logs de Invocar/Registrar/etc. en
tiempo real. `Ctrl+C` para salir.

```powershell
docker ps --filter "name=desktop-control-plane"
docker port desktop-control-plane
```
Diagnóstico a nivel de Docker (no de Kubernetes): confirma si el nodo del
clúster (que en Docker Desktop corre como un contenedor usando `kind`) tiene
el puerto realmente publicado hacia Windows. Útil si el NodePort no responde
desde el navegador aunque el Service esté bien configurado.

```powershell
Test-NetConnection -ComputerName localhost -Port 30093
```
Prueba si el puerto abre conexión TCP a nivel de sistema operativo, sin
pasar por HTTP — separa un problema de red/Docker Desktop de un problema de
la app en sí.

---

## 5. Actualizar después de un cambio de código

Si modificas el backend, tienes que reconstruir y volver a subir la imagen,
y luego forzar que Kubernetes la vuelva a descargar (por defecto, si el tag
es `:latest`, un pod ya corriendo NO se actualiza solo):

```powershell
docker build -t vil-tr-app:latest .
docker tag vil-tr-app:latest marilynvilcapuma/ht-232-44-marilyn-vilcapuma:latest
docker push marilynvilcapuma/ht-232-44-marilyn-vilcapuma:latest
kubectl rollout restart deployment/marilyn-deployment -n marilyn
```
El último comando (`rollout restart`) fuerza a Kubernetes a recrear los 3
pods uno por uno (sin downtime total), descargando la imagen `:latest`
actualizada de Docker Hub.

```powershell
kubectl rollout status deployment/marilyn-deployment -n marilyn
```
Muestra el progreso del reemplazo de pods en tiempo real.

---

## 6. Eliminar recursos

### Eliminar todo de una vez (recomendado)
```powershell
kubectl delete namespace marilyn
```
Borra el namespace y, en cascada, TODO lo que contiene (Secret, Deployment,
Service, ReplicaSet, pods) — no hace falta borrar uno por uno.

### Eliminar uno por uno (si necesitas mantener el namespace)
```powershell
kubectl delete -f k8/marilyn-service.yml
kubectl delete -f k8/marilyn-deployment.yml
kubectl delete -f k8/marilyn-secret.yml
kubectl delete -f k8/marilyn-namespace.yml
```
Orden inverso al de creación — se borra primero lo que depende, al final la
base (namespace).

### Reiniciar el clúster completo de Docker Desktop (último recurso)
Docker Desktop → pestaña Kubernetes → botón **Stop**, esperar, luego
**Start**, o usar la opción **Reset Kubernetes Cluster**. Esto borra
absolutamente todo lo que hayas creado (no solo tu namespace) y reinicia el
clúster desde cero — después hay que volver a aplicar los 4 manifiestos del
paso 1.

---

## Resumen — comandos de ayuda generales

```powershell
kubectl get namespaces
```
Lista todos los namespaces del clúster (para confirmar que `marilyn` existe
o ver qué otros namespaces hay).

```powershell
kubectl config current-context
```
Muestra a qué clúster está apuntando `kubectl` ahora mismo (útil si alguna
vez trabajas con más de un clúster, ej. local vs. cloud).

```powershell
kubectl explain service.spec
```
Documentación integrada de cualquier campo de un recurso — útil para
recordar qué opciones acepta `type`, `ports`, etc. sin salir de la terminal.
