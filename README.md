# 4UD

Java + MySQL web app with a static frontend and a lightweight Java backend.

## Structure

- `frontend/`: HTML, CSS, and browser-side JavaScript
- `backend/src/App.java`: backend server and API logic
- `backend/sql/schema.sql`: MySQL schema
- `backend/config.properties.example`: sample DB configuration

## Run

1. Update `backend/config.properties` with your MySQL settings.
2. Start the app:

```bat
backend\run-java.bat
```

3. Open:

```text
http://localhost:3000
```

## Features

- Contact form with validation and MySQL persistence
- Admin and student login backed by MySQL
- Hashed passwords
- Admin dashboard with enquiry review and hide action

## Deploy

This project is prepared for Railway-style deployment with Docker.

### Required environment variables

- `PORT`
- `DB_URL` or `MYSQL_URL`
- `DB_USER` or `MYSQLUSER`
- `DB_PASSWORD` or `MYSQLPASSWORD`

Example:

```text
DB_URL=jdbc:mysql://<host>:3306/myproject?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
DB_USER=myproject_user
DB_PASSWORD=your_password
```

### Railway

1. Create a new project in Railway.
2. Add a MySQL service.
3. Deploy this GitHub repo as a service using the root `Dockerfile`.
4. Set the database environment variables on the deployed service.
5. Open the generated public Railway URL and share it.
