package com.dockerExample.demo.controller;

import com.dockerExample.demo.entity.Greeting;
import com.dockerExample.demo.repository.GreetingRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class GreetingController {

    private final GreetingRepository greetingRepository;

    public GreetingController(GreetingRepository greetingRepository) {
        this.greetingRepository = greetingRepository;
    }

    @GetMapping("/greetings/add/{message}")
    public Greeting addGreeting(@PathVariable String message) {
        return greetingRepository.save(new Greeting(message));
    }

    @GetMapping("/greetings")
    public List<Greeting> getGreetings() {
        return greetingRepository.findAll();
    }
}
