FROM nginx:1.29.8-alpine
RUN apk update && apk upgrade --no-cache \
    && sed -i '/^user /d' /etc/nginx/nginx.conf \
    && chown -R 1000:1000 \
       /var/cache/nginx \
       /var/log/nginx \
       /etc/nginx/conf.d
COPY nginx.conf /etc/nginx/conf.d/default.conf
USER 1000
EXPOSE 80
HEALTHCHECK --interval=20s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -qO /dev/null http://localhost:80/gateway/health || exit 1
CMD ["nginx", "-g", "daemon off;"]
