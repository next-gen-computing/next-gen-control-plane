# ── Dashboard (nginx serving static files) ──────
FROM nginx:alpine

# Copy nginx config
COPY dashboard/nginx.conf /etc/nginx/conf.d/default.conf

# Copy static frontend files
COPY dashboard/html/overview.html       /usr/share/nginx/html/overview.html
COPY dashboard/html/performance.html       /usr/share/nginx/html/performance.html
COPY dashboard/html/nodes.html       /usr/share/nginx/html/nodes.html
COPY dashboard/css/             /usr/share/nginx/html/css/
COPY dashboard/js/              /usr/share/nginx/html/js/

EXPOSE 80

