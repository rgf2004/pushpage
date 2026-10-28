FROM nginx:alpine
# Rendered to conf.d/default.conf at start by the nginx image (envsubst on defined env vars only)
COPY nginx/default.conf /etc/nginx/templates/default.conf.template
ENV VIEWER_WRAPPER_ENABLED=false
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
