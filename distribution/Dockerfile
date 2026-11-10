FROM python:3.13-alpine
RUN addgroup -S -g 10001 mirror \
    && adduser -S -D -H -u 10001 -G mirror mirror \
    && mkdir /data \
    && chown mirror:mirror /data
WORKDIR /app
COPY mirror.py /app/mirror.py
USER 10001:10001
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
CMD ["python", "/app/mirror.py"]
