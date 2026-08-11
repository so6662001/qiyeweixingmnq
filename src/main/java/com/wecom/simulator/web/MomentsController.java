package com.wecom.simulator.web;

import com.wecom.simulator.runtime.ChannelRuntime;
import com.wecom.simulator.security.WebhookUrlValidator;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/moments")
public class MomentsController extends AbstractMomentsController {

    public MomentsController(ChannelRuntime wecomRuntime, WebhookUrlValidator urlValidator) {
        super(wecomRuntime, urlValidator);
    }
}
