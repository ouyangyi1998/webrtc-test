package com.example.webrtc.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Arrays;
import java.util.List;

@Controller
public class WebController {

    @Value("${app.stun.urls}")
    private String stunUrls;

    @Value("${app.turn.urls:}")
    private String turnUrls;

    @Value("${app.turn.username:}")
    private String turnUsername;

    @Value("${app.turn.password:}")
    private String turnPassword;

    @GetMapping({"/", "/room"})
    public String index(Model model) {
        model.addAttribute("stunServers", toList(stunUrls));
        model.addAttribute("turnServers", toList(turnUrls));
        model.addAttribute("turnUsername", turnUsername);
        model.addAttribute("turnPassword", turnPassword);
        return "room";
    }

    private List<String> toList(String csv) {
        if (!StringUtils.hasText(csv)) {
            return java.util.Collections.emptyList();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toList());
    }
}
