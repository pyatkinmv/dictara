# Миграция транскрайбера на Cloud Run

Переносим сервис транскрибации с CPU на виртуальной машине в Google Cloud Run
с моделями, смонтированными из GCS. Два этапа: сначала CPU для проверки
пайплайна, потом GPU для скорости.

---

## Архитектура

**До:**
```
gateway (VM) → HTTP → transcriber (VM, Docker, CPU)
                       модели: Docker volume на диске VM
```

**После:**
```
gateway (VM) → HTTPS + Auth → transcriber (Cloud Run, GPU)
                               модели: GCS bucket примонтирован как /models
```

---

## Этап 1 — CPU (дёшево, проверяем пайплайн)

Минимальный Cloud Run без GPU — проверяем что всё работает: монтирование GCS,
авторизация, роутинг, стриминг прогресса. GPU добавим потом.

Конфиг: 2 vCPU, 4 GB RAM, min-instances 0. Стоимость: ~$0 в простое.

---

### Шаг 1 — Создать GCS bucket и загрузить модели

**Что делать:**

1. Создать bucket:
```
gcloud storage buckets create gs://gen-lang-client-0721625814-models --location=europe-west4 --uniform-bucket-level-access
```

2. Скопировать модели с VM в bucket. Подключиться к VM по SSH и выполнить:
```
docker run --rm -v dictara_model-cache:/models -v ~/.config/gcloud:/root/.config/gcloud google/cloud-sdk:slim gsutil -m cp -r /models/* gs://gen-lang-client-0721625814-models/
```

**Проверка через командную строку:**
```bash
gcloud storage ls gs://gen-lang-client-0721625814-models/
# Должны появиться файлы моделей Whisper и папки кеша HuggingFace
```

**Проверка через консоль GCP:**
- Открыть console.cloud.google.com
- Cloud Storage → Buckets
- Должен появиться `gen-lang-client-0721625814-models` с файлами внутри

---

### Шаг 2 — Адаптировать Dockerfile

**Что делать:**

Текущий Dockerfile скачивает модели при первом запуске через переменную
`WHISPER_MODELS`. В Cloud Run модели уже будут в `/models` через mount —
скачивать ничего не нужно.

Проверить `transcriber/Dockerfile` и `transcriber/app.py`:
- Если модели скачиваются при старте: добавить проверку — скачивать только
  если `/models` пустая (для локальной разработки).
- Переменная `HF_HOME` должна по-прежнему указывать на `/models`.

**Проверка:**
```bash
docker build -t transcriber-test ./transcriber
docker run --rm -e HF_HOME=/models -v /tmp/empty:/models transcriber-test python -c "print('ok')"
# Должен запуститься без попытки скачать модели
```

---

### Шаг 3 — Запушить образ в Artifact Registry

**Что делать:**

1. Создать репозиторий (один раз):
```
gcloud artifacts repositories create dictara --repository-format=docker --location=europe-west4
```

2. Собрать и запушить образ:
```
gcloud builds submit ./transcriber --tag europe-west4-docker.pkg.dev/gen-lang-client-0721625814/dictara/transcriber:latest
```

**Проверка через командную строку:**
```
gcloud artifacts docker images list europe-west4-docker.pkg.dev/gen-lang-client-0721625814/dictara
```

**Проверка через консоль GCP:**
- Artifact Registry → Repositories
- Открыть репозиторий `dictara`
- Должен быть образ `transcriber` с актуальной датой

---

### Шаг 4 — Задеплоить Cloud Run сервис (CPU)

**Что делать:**

```
gcloud run deploy transcriber --image europe-west4-docker.pkg.dev/gen-lang-client-0721625814/dictara/transcriber:latest --region europe-west4 --cpu 2 --memory 4Gi --timeout 3600 --concurrency 1 --min-instances 0 --max-instances 3 --no-allow-unauthenticated --add-volume name=models,type=cloud-storage,bucket=gen-lang-client-0721625814-models --add-volume-mount volume=models,mount-path=/models --set-env-vars HF_TOKEN=ВАШ_HF_TOKEN,HF_HOME=/models,WHISPER_MODELS=small
```

Пояснения:
- `--concurrency 1` — одна транскрибация на инстанс одновременно
- `--no-allow-unauthenticated` — только gateway может обращаться к сервису
- На первом этапе грузим только модель `small` — быстрее стартует

**Проверка через командную строку:**
```bash
gcloud run services describe transcriber --region europe-west4
# Status должен быть "Ready"

# Получить URL сервиса
gcloud run services describe transcriber --region europe-west4 --format="value(status.url)"

# Проверить что сервис отвечает (403 = норма, значит сервис живой)
curl https://transcriber-xxxx-ew.a.run.app/health
# Ожидаем: 403 Forbidden (не 502/503)
```

**Проверка через консоль GCP:**
- Cloud Run → Services
- Должен появиться сервис `transcriber` со статусом ✅ Ready
- Открыть сервис → вкладка Logs — видны логи запуска

---

### Шаг 5 — Настроить авторизацию gateway → Cloud Run

**Что делать:**

Cloud Run требует подписанный токен в каждом запросе. Gateway нужен
Service Account с правом вызывать transcriber.

1. Создать Service Account для gateway:
```
gcloud iam service-accounts create dictara-gateway --display-name="Dictara Gateway"
```

2. Выдать права на вызов Cloud Run сервиса:
```
gcloud run services add-iam-policy-binding transcriber --region europe-west4 --member="serviceAccount:dictara-gateway@gen-lang-client-0721625814.iam.gserviceaccount.com" --role="roles/run.invoker"
```

3. Создать и скачать ключ:
```
gcloud iam service-accounts keys create gateway-sa-key.json --iam-account=dictara-gateway@gen-lang-client-0721625814.iam.gserviceaccount.com
```

4. Скопировать ключ на VM, прописать в окружении gateway:
```
GOOGLE_APPLICATION_CREDENTIALS=/path/to/gateway-sa-key.json
```

5. В коде gateway добавить Google OIDC токен к каждому запросу к transcriber:
```
Authorization: Bearer <id_token>
```
Использовать библиотеку `google-auth-library-oauth2-http` для Kotlin/Java.

**Проверка через командную строку:**
```
gcloud auth print-identity-token --audiences=https://transcriber-xxxx-ew.a.run.app
# Скопировать токен и вызвать:
curl -H "Authorization: Bearer ВАШ_ТОКЕН" https://transcriber-xxxx-ew.a.run.app/health
# Ожидаем: 200 OK
```

**Проверка через консоль GCP:**
- IAM & Admin → Service Accounts
- Должен появиться `dictara-gateway`
- Cloud Run → transcriber → вкладка Security → в разделе Authentication
  должен быть `dictara-gateway` с ролью `roles/run.invoker`

---

### Шаг 6 — Переключить gateway на Cloud Run

**Что делать:**

1. На VM обновить `.env`:
```
TRANSCRIBER_URL=https://transcriber-xxxx-ew.a.run.app
```

2. Закомментировать сервис `transcriber` в `docker-compose.yml`
   (не удалять — для быстрого отката).

3. Перезапустить gateway:
```bash
docker compose up -d gateway
```

**Проверка через командную строку:**
```
docker compose logs gateway --tail=50 | grep -i transcrib

gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=transcriber" --limit=50 --format="table(timestamp,textPayload)"
```

**Проверка через консоль GCP:**
- Cloud Run → transcriber → вкладка Metrics — должны появиться запросы
- Cloud Run → transcriber → вкладка Logs — должны быть логи транскрибации

**Проверка end-to-end:**
- Отправить короткое голосовое в Telegram бот
- Должен ответить транскрипцией

---

## Этап 2 — GPU (L4)

После того как этап 1 стабильно работает, передеплоить с GPU.
Меняются только несколько параметров:

```
gcloud run deploy transcriber --image europe-west4-docker.pkg.dev/gen-lang-client-0721625814/dictara/transcriber:latest --region europe-west4 --cpu 4 --memory 16Gi --gpu 1 --gpu-type nvidia-l4 --timeout 3600 --concurrency 1 --min-instances 0 --max-instances 2 --no-allow-unauthenticated --add-volume name=models,type=cloud-storage,bucket=gen-lang-client-0721625814-models --add-volume-mount volume=models,mount-path=/models --set-env-vars HF_TOKEN=ВАШ_HF_TOKEN,HF_HOME=/models,WHISPER_MODELS=small,turbo
```

Перед деплоем проверить что GPU доступен в регионе:
```
gcloud run locations list --filter="FEATURES:GPU"
```

**Проверка:**
- Те же шаги что в Шаге 6
- Сравнить время: аудио на 10 мин на CPU занимало 20+ мин,
  на L4 GPU должно занять меньше 2 мин

---

## Передача больших файлов через GCS (фикс 413 Request Entity Too Large)

После переезда на Cloud Run обнаружился **жёсткий лимит на размер HTTP-запроса —
32 МиБ** (инфраструктурное ограничение Google Front End, конфигурацией не
отключается). Файлы крупнее лимита получали `413 Request Entity Too Large` на
этапе `gateway → transcriber`, потому что весь файл целиком пересылался в теле
multipart POST.

**Решение**: вместо передачи байтов файла по HTTP — gateway загружает аудио в
бакет Google Cloud Storage и передаёт transcriber'у только ссылку
(`gs://bucket/key`, несколько байт в query-параметре `storage_uri`).
Transcriber скачивает файл из бакета напрямую через GCS API — в обход HTTP body
limit полностью.

```
gateway ──upload bytes──> GCS bucket (uploads)
gateway ──POST /transcribe?...&storage_uri=gs://bucket/audio/{id}/{name}──> transcriber
transcriber ──download via google-cloud-storage client──> /tmp ──> (как раньше)
```

**Обратная совместимость**: режим выбирается по конфигурации
(`dictara.storage.gcs.bucket` / `GCS_UPLOADS_BUCKET`). Если бакет не задан (как
в локальном docker-compose), gateway продолжает работать по старой схеме —
BLOB в Postgres (`audio_content`) + multipart upload к transcriber'у. Старые
записи в БД (`audio_meta.storage_uri IS NULL`) тоже обслуживаются старым путём.

### Инфраструктура

1. **Bucket для временных загрузок** (отдельно от bucket'а с моделями):
```
gcloud storage buckets create gs://gen-lang-client-0721625814-uploads --location=europe-west4 --uniform-bucket-level-access
```

2. **Lifecycle-правило** — автоматическая очистка через **90 дней**. Это
   единственный механизм очистки — приложение объекты не удаляет (упрощает
   код, убирает необходимость в retry/cleanup-логике):
```
gcloud storage buckets update gs://gen-lang-client-0721625814-uploads --lifecycle-file=lifecycle.json
# lifecycle.json: {"rule":[{"action":{"type":"Delete"},"condition":{"age":90}}]}
```

3. **IAM**:
   - service account VM (через который gateway использует ADC) → `roles/storage.objectAdmin` на новый bucket;
   - service account Cloud Run transcriber → `roles/storage.objectViewer` на новый bucket.

4. **Переменная окружения** `GCS_UPLOADS_BUCKET` — задать в `.env` для gateway
   (имя bucket'а на проде, пусто локально → старый BLOB-путь).

### Проверка

1. Отправить небольшой файл — должен пройти как раньше (или BLOB-путём локально,
   или через GCS на проде, в зависимости от `GCS_UPLOADS_BUCKET`).
2. Отправить файл больше 32 МБ — раньше падал с 413, теперь должен пройти.
3. В логах gateway искать `Uploaded audio to gs://...` — подтверждает новый путь.
4. `gcloud storage ls gs://gen-lang-client-0721625814-uploads/audio/` — объект
   должен присутствовать (очистка не выполняется приложением, это ожидаемо).
5. `SELECT storage_uri FROM audio_meta ORDER BY created_at DESC LIMIT 5;` —
   для новых файлов должна быть заполнена ссылка `gs://...`, для старых — `NULL`.

---

## Откат

В любой момент вернуться на VM транскрайбер:

1. Раскомментировать `transcriber` в `docker-compose.yml`
2. Установить `TRANSCRIBER_URL=http://transcriber:8000` в `.env`
3. `docker compose up -d`

Изменений в коде не нужно.
