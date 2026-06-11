# Configuration

All variables are set in a `.env` file in the project root and passed to the containers via `docker-compose.yml`.

| Variable | Description | Default |
|----------|-------------|---------|
| `APP_SERVER_URL` | Full public URL of the service. Used to build the `url` field in publish responses. | `http://localhost:8080` |
| `NGINX_PORT` | Host port nginx binds to. | `8080` |
| `CLEANUP_RETENTION_DAYS` | Days to retain pages before the cleanup job removes them. | `30` |
| `CLEANUP_SCHEDULE` | Cron expression controlling how often the cleanup job runs. | `0 0 * * * *` (hourly) |
| `MAX_FILE_SIZE` | Max HTML payload the publisher accepts (app-level check). | `1MB` |
| `MAX_REQUEST_SIZE` | Servlet-level request size ceiling. Should exceed `MAX_FILE_SIZE`. | `10MB` |
