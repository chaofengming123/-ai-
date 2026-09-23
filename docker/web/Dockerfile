FROM public.ecr.aws/docker/library/nginx:stable-alpine
COPY frontend/dist/ /usr/share/nginx/html/
COPY docker/web/default.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
