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
