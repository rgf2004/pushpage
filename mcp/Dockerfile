FROM python:3.15-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY server.py .
COPY pushpage_mcp/ pushpage_mcp/

EXPOSE 8000

CMD ["python", "server.py"]
