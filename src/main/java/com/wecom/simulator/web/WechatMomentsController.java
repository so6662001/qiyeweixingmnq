package com.wecom.simulator.web;

import com.wecom.simulator.runtime.ChannelRuntime;
import com.wecom.simulator.security.WebhookUrlValidator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wechat/moments")
public class WechatMomentsController extends AbstractMomentsController {

    public WechatMomentsController(
            @Qualifier("wechatRuntime") ChannelRuntime wechatRuntime,
            WebhookUrlValidator urlValidator
    ) {
        super(wechatRuntime, urlValidator);
    }
}
