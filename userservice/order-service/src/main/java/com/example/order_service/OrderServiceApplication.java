package com.example.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class OrderServiceApplication {
	public static void main(String[] args) {
		SpringApplication.run(OrderServiceApplication.class, args);
	}

	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate();
	}
}

@RestController
class OrderController {

	private final RestTemplate restTemplate;

	OrderController(RestTemplate restTemplate) {
		this.restTemplate = restTemplate;
	}

	@GetMapping("/orders")
	public String getOrders() {
		// "user-service" here is the K8s Service DNS name — not localhost, not an IP
		String users = restTemplate.getForObject("http://user-service:8081/users", String.class);
		return "Orders placed by: " + users;
	}

	@GetMapping("/orders/secret-check")
	public String secretCheck() {
		String apiKey = System.getenv("API_KEY");
		if (apiKey == null) {
			return "API_KEY not found!";
		}
		return "API_KEY is present, starts with: " + apiKey.substring(0, 4) + "****";
	}
}