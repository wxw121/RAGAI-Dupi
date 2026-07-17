FROM python:3.11-slim-trixie

WORKDIR /app

RUN apt-get update \
    && apt-get upgrade -y \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ARG PIP_EXTRA_INDEX_URL=https://pypi.org/simple
ARG TORCH_INDEX_URL=https://download.pytorch.org/whl/cpu
RUN pip install --no-cache-dir \
    --default-timeout=300 \
    -i ${PIP_INDEX_URL} \
    --extra-index-url ${PIP_EXTRA_INDEX_URL} \
    --upgrade pip setuptools==83.0.0 wheel \
    && pip install --no-cache-dir \
    --default-timeout=300 \
    --retries=20 \
    --index-url ${TORCH_INDEX_URL} \
    torch==2.10.0+cpu \
    && pip install --no-cache-dir \
    --default-timeout=300 \
    -i ${PIP_INDEX_URL} \
    --extra-index-url ${PIP_EXTRA_INDEX_URL} \
    -r requirements.txt

COPY app ./app

RUN mkdir -p /home/worker /models/huggingface \
    && chown -R 65534:65534 /app /home/worker /models/huggingface

ENV HOME=/home/worker \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/models/huggingface \
    SENTENCE_TRANSFORMERS_HOME=/models/huggingface \
    TRANSFORMERS_CACHE=/models/huggingface

USER 65534:65534

CMD ["python", "-m", "app.main"]
