FROM nginx:alpine
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --chmod=755 nginx/20-realip.sh /docker-entrypoint.d/20-realip.sh
COPY nginx/index.html /usr/share/nginx/html/index.html
COPY nginx/dashboard.html /usr/share/nginx/html/dashboard.html
COPY nginx/docs.html /usr/share/nginx/html/docs.html
COPY nginx/404.html /usr/share/nginx/html/404.html
COPY nginx/static/ /usr/share/nginx/html/static/
COPY nginx/terms.html /usr/share/nginx/html/terms.html
COPY llms.txt /usr/share/nginx/html/llms.txt
COPY nginx/robots.txt /usr/share/nginx/html/robots.txt
COPY nginx/sitemap.xml /usr/share/nginx/html/sitemap.xml
COPY nginx/static/og-image.png /usr/share/nginx/html/static/og-image.png

# Placeholder so the include in default.conf always resolves, even if the entrypoint script is bypassed
RUN touch /etc/nginx/realip.conf
