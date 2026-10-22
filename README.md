# Cloud-Based Product Return Management Platform

A full-stack educational project for registering product return requests, tracking their status, searching requests, and updating return decisions.

## Technology
- Frontend: HTML, CSS, JavaScript
- Backend: Java 17+ with the built-in `HttpServer`
- Storage: local JSON file (`data/returns.json`)
- Dependencies: none

## Features
- Submit a product return request with order number, product, reason, and customer name
- View and search return requests
- Filter requests by status
- Update request status (Requested, Approved, Rejected, Received, Refunded)
- Dashboard summary counts
- Responsive layout
- Local JSON persistence

## Run locally
Install Java JDK 17 or newer, extract the ZIP, and open a terminal in this project folder.

### Windows
```bat
javac --add-modules jdk.httpserver -d out src\main\java\ProductReturnServer.java
java --add-modules jdk.httpserver -cp out ProductReturnServer
```

### macOS / Linux
```bash
javac --add-modules jdk.httpserver -d out src/main/java/ProductReturnServer.java
java --add-modules jdk.httpserver -cp out ProductReturnServer
```

Open http://localhost:8080. Keep the terminal open while using the app. Stop it with Ctrl+C.

The server creates `data/returns.json` on first run. This file is ignored by Git to avoid uploading local customer/order data.

## API
- `GET /api/returns` — list return requests
- `POST /api/returns` — create a request
- `PATCH /api/returns?id=RETURN_ID` — update status
- `DELETE /api/returns?id=RETURN_ID` — delete a request

## GitHub upload
Create a repository, extract this ZIP, then use **Add file → Upload files** to upload the extracted project files and folders.

## Limitations
This is an educational local demo, not a production commerce system. It has no login, role-based permissions, payment gateway, shipping carrier integration, or cloud database. Do not enter real customer personal information. Production deployment needs authentication, authorization, input validation, secure transport, audit logs, backups, and managed storage.
