FROM mwader/static-ffmpeg:latest AS ffmpeg

FROM python:3.12-slim
ARG GIT_COMMIT=unknown
ARG BUILD_TIME=unknown
ENV GIT_COMMIT=$GIT_COMMIT
ENV BUILD_TIME=$BUILD_TIME

COPY --from=ffmpeg /ffmpeg /usr/local/bin/ffmpeg

WORKDIR /app

COPY requirements.txt .
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install -r requirements.txt \
        --index-url https://download.pytorch.org/whl/cpu \
        --extra-index-url https://pypi.org/simple/

COPY transcriber.py jobs.py app.py ./

EXPOSE 8000

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
