package com.dockerExample.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GetHelloController {

    @GetMapping("/hello")
    public String getHello() {
        return "Hello From Docker";
    }
}
