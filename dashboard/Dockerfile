# ── Dashboard (nginx serving static files) ──────
FROM nginx:alpine

# Copy nginx config
COPY dashboard/nginx.conf /etc/nginx/conf.d/default.conf

# Copy static frontend files
COPY dashboard/index.html       /usr/share/nginx/html/index.html
COPY dashboard/css/             /usr/share/nginx/html/css/
COPY dashboard/js/              /usr/share/nginx/html/js/

EXPOSE 80
