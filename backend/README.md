# Backend

Spring Boot backend for Smart Assessment AI.

## Local configuration

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/smart_assessment_ai"
$env:SPRING_DATASOURCE_USERNAME = "postgres"
$env:SPRING_DATASOURCE_PASSWORD = "<password>"
$env:APP_JWT_SECRET_BASE64 = "<base64-32-byte-secret>"
./mvnw spring-boot:run
```
