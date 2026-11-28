FROM node:26.13.1-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29.8-alpine
COPY nginx.conf /etc/nginx/nginx.conf
COPY security-headers.conf /etc/nginx/security-headers.conf
COPY --from=build /app/dist/ui/browser /usr/share/nginx/html
# Official image's entrypoint scripts edit /etc/nginx/conf.d as root; we run non-root with our own config.
ENTRYPOINT []
USER nginx
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
