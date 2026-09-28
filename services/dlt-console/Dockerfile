FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml ./
COPY dlt_console dlt_console
RUN pip install --no-cache-dir . && useradd --system --uid 1001 app
USER app
ENV KAFKA_BOOTSTRAP=kafka:9092
EXPOSE 8090
CMD ["uvicorn", "dlt_console.app:app", "--host", "0.0.0.0", "--port", "8090"]
