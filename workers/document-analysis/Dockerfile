# Python document-analysis worker (DOCX/PDF parsing). Expects pyproject.toml and src/ here.
FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY pyproject.toml .
COPY src ./src
RUN pip install .
USER app
EXPOSE 8082
CMD ["python","-m","die_document_worker"]
