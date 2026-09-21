# Official source release includes the October 2025 security fix.
FROM public.ecr.aws/docker/library/golang:1.24.8-alpine AS build
RUN apk add --no-cache git ca-certificates
RUN CGO_ENABLED=0 go install github.com/minio/minio@RELEASE.2025-10-15T17-29-55Z

FROM public.ecr.aws/docker/library/alpine:3.22
RUN apk add --no-cache ca-certificates curl
COPY --from=build /go/bin/minio /usr/local/bin/minio
EXPOSE 9000 9001
ENTRYPOINT ["minio"]
CMD ["server", "/data", "--console-address", ":9001"]
