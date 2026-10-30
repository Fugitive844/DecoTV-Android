package io.github.fugitive844.decotv.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.net.ApiClient;
import io.github.fugitive844.decotv.net.Tasks;

/** 注册：与网页端 /register 一致，用户名 + 两次密码 + 图形验证码；注册成功后直接登录。 */
public class RegisterActivity extends AppCompatActivity {
    static final String EXTRA_SERVER = "server";

    private String server;
    private TextInputEditText username, password, confirm, code;
    private WebView captchaView;
    private MaterialButton submit;
    private String sessionId = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        server = getIntent().getStringExtra(EXTRA_SERVER);
        if (server == null || server.isEmpty()) server = DecoApp.get().prefs.server();
        setTitle("注册账号");
        Style.systemBars(this);
        LinearLayout form = Ui.column(this, 24);
        form.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView heading = Style.text(this, "注册账号", 28, 900, Style.text(this));
        form.addView(heading, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView hint = Style.text(this, "服务器：" + server + "\n用户名 3-20 位字母、数字或下划线，不能全是数字；密码至少 6 位，不能全是数字。", 13, 400, Style.text2(this));
        hint.setPadding(0, Ui.dp(this, 8), 0, 0);
        hint.setLineSpacing(Ui.dp(this, 2), 1f);
        form.addView(hint);
        username = field(form, "用户名", InputType.TYPE_CLASS_TEXT);
        password = field(form, "密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        confirm = field(form, "确认密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout captchaRow = Ui.row(this);
        captchaView = new WebView(this);
        captchaView.setBackgroundColor(0);
        captchaView.setVerticalScrollBarEnabled(false);
        captchaView.setHorizontalScrollBarEnabled(false);
        captchaView.setContentDescription("验证码图片，点击刷新");
        // 验证码是一段 SVG，只做展示：不开 JS，点击由外层处理
        captchaView.getSettings().setJavaScriptEnabled(false);
        captchaView.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_UP) loadCaptcha();
            return true;
        });
        captchaRow.addView(captchaView, new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 40)));
        TextView refresh = Ui.text(this, "看不清？点图片换一张", 12, false);
        refresh.setAlpha(0.6f);
        refresh.setPadding(Ui.dp(this, 12), 0, 0, 0);
        refresh.setOnClickListener(v -> loadCaptcha());
        captchaRow.addView(refresh);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(this, 16);
        form.addView(captchaRow, rlp);
        code = field(form, "验证码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        submit = new MaterialButton(this);
        Style.primaryButton(submit);
        submit.setText("注册并登录");
        submit.setOnClickListener(v -> register());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 16);
        form.addView(submit, lp);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        setContentView(scroll);
        loadCaptcha();
    }

    private TextInputEditText field(LinearLayout parent, String hint, int type) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(hint);
        TextInputEditText edit = Ui.input(layout, type);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        parent.addView(layout, lp);
        return edit;
    }

    private void loadCaptcha() {
        code.setText("");
        Tasks.run(() -> DecoApp.get().api.captcha(server), c -> {
            sessionId = c.sessionId;
            String html = "<html><head><style>html,body{margin:0;padding:0;overflow:hidden}svg{width:100%;height:100%}</style></head><body>"
                    + c.svg + "</body></html>";
            captchaView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        }, e -> Ui.toast(this, "获取验证码失败：" + e.getMessage()));
    }

    private void register() {
        String user = String.valueOf(username.getText()).trim();
        String pass = String.valueOf(password.getText());
        String again = String.valueOf(confirm.getText());
        String captcha = String.valueOf(code.getText()).trim();
        if (user.isEmpty() || pass.isEmpty() || captcha.isEmpty()) {
            Ui.toast(this, "请填写用户名、密码和验证码");
            return;
        }
        if (!pass.equals(again)) {
            Ui.toast(this, "两次密码不一致");
            return;
        }
        submit.setEnabled(false);
        submit.setText("注册中…");
        String session = sessionId;
        Tasks.run(() -> {
            ApiClient api = DecoApp.get().api;
            api.register(server, user, pass, captcha, session);
            return api.login(server, user, pass);
        }, cookie -> {
            DecoApp.get().prefs.saveLogin(server, user, cookie);
            Ui.toast(this, "注册成功");
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        }, error -> {
            submit.setEnabled(true);
            submit.setText("注册并登录");
            Ui.toast(this, "注册失败：" + error.getMessage());
            // 验证码用过或错误次数过多都要换新的
            loadCaptcha();
        });
    }

    @Override protected void onDestroy() {
        if (captchaView != null) captchaView.destroy();
        super.onDestroy();
    }
}
