FROM nginx:alpine
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/index.html /usr/share/nginx/html/index.html
COPY nginx/dashboard.html /usr/share/nginx/html/dashboard.html
COPY nginx/docs.html /usr/share/nginx/html/docs.html
COPY nginx/404.html /usr/share/nginx/html/404.html
COPY nginx/static/ /usr/share/nginx/html/static/
COPY nginx/terms.html /usr/share/nginx/html/terms.html
COPY llms.txt /usr/share/nginx/html/llms.txt
