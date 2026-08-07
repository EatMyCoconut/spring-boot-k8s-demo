package com.dockerExample.demo.repository;

import com.dockerExample.demo.entity.Greeting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface  GreetingRepository extends JpaRepository<Greeting, Long> {
}
