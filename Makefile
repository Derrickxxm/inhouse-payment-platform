.PHONY: demo test

demo:
	./mvnw -q spring-boot:run -Dspring-boot.run.arguments=demo

test:
	./mvnw -B test
