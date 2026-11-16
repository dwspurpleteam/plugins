IMAGE ?= ghcr.io/dwspurpleteam/attendance
override TAG := $(shell git rev-parse HEAD)
PLATFORM ?= linux/amd64
DOCKERFILE ?= Dockerfile

.PHONY: image-build
image-build:
	docker buildx build --platform $(PLATFORM) --load -t $(IMAGE):$(TAG) -f $(DOCKERFILE) .

.PHONY: image-push
image-push:
	docker push $(IMAGE):$(TAG)
