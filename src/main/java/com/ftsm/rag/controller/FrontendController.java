package com.ftsm.rag.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FrontendController {

    private final Resource index = new ClassPathResource("static/index.html");

    @GetMapping(
            value = {"/", "/settings", "/manage", "/dashboard", "/prompts"},
            produces = MediaType.TEXT_HTML_VALUE
    )
    public Resource index() {
        return index;
    }
}
