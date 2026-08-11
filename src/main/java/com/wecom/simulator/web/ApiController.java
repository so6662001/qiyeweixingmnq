package com.wecom.simulator.web;

import com.wecom.simulator.runtime.ChannelRuntime;
import com.wecom.simulator.security.WebhookUrlValidator;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController extends AbstractSimulatorApiController {

    public ApiController(ChannelRuntime wecomRuntime, WebhookUrlValidator webhookUrlValidator) {
        super(wecomRuntime, webhookUrlValidator);
    }
}
