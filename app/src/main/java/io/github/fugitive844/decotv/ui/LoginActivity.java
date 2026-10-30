package io.github.fugitive844.decotv.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import io.github.fugitive844.decotv.DecoApp;
import io.github.fugitive844.decotv.data.Prefs;
import io.github.fugitive844.decotv.net.ApiClient;
import io.github.fugitive844.decotv.net.Relay;
import io.github.fugitive844.decotv.net.Tasks;
import io.github.fugitive844.decotv.update.AppUpdater;

public class LoginActivity extends AppCompatActivity {
    private TextInputEditText server, username, password, accessKey;
    private MaterialButton submit;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Prefs prefs = DecoApp.get().prefs;
        if (prefs.loggedIn()) {
            openMain();
            return;
        }
        setTitle("登录 DecoTV");
        Style.systemBars(this);
        LinearLayout form = Ui.column(this, 24);
        form.setGravity(Gravity.CENTER_HORIZONTAL);
        form.setPadding(Ui.dp(this, 24), Ui.dp(this, 56), Ui.dp(this, 24), Ui.dp(this, 24));
        TextView title = Style.text(this, "", 34, 900, Style.text(this));
        android.text.SpannableString logo = new android.text.SpannableString("DecoTV");
        logo.setSpan(new android.text.style.ForegroundColorSpan(Style.accent(this)), 4, 6, 0);
        title.setText(logo);
        title.setGravity(Gravity.CENTER);
        form.addView(title);
        TextView slogan = Style.text(this, "登录你的影视服务器", 14, 400, Style.text2(this));
        slogan.setGravity(Gravity.CENTER);
        slogan.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        form.addView(slogan);
        server = field(form, "服务器地址", InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(prefs.server());
        username = field(form, "用户名", InputType.TYPE_CLASS_TEXT);
        username.setText(prefs.username());
        password = field(form, "密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        accessKey = field(form, "访问秘钥（选填）", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        accessKey.setText(prefs.accessKey());
        TextView keyHint = Style.text(this, ApiClient.ACCESS_KEY_HINT + "；不填也可以登录后在「我的 → 访问秘钥」中填写", 12, 400, Style.text3(this));
        keyHint.setPadding(0, Ui.dp(this, 6), 0, 0);
        form.addView(keyHint);
        submit = new MaterialButton(this);
        Style.primaryButton(submit);
        submit.setTextSize(16);
        submit.setText("登录");
        submit.setOnClickListener(v -> login());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 16);
        form.addView(submit, lp);
        register = new MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle);
        register.setText("没有账号？立即注册");
        register.setVisibility(android.view.View.GONE);
        register.setOnClickListener(v -> startActivity(new Intent(this, RegisterActivity.class)
                .putExtra(RegisterActivity.EXTRA_SERVER, Relay.normalizeServer(String.valueOf(server.getText())))));
        form.addView(register);
        TextView copyright = Ui.copyright(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.topMargin = Ui.dp(this, 32);
        form.addView(copyright, cp);
        // 与网页端一致：服务端开放注册时才显示入口；改了服务器地址后重新检查
        server.setOnFocusChangeListener((v, focused) -> { if (!focused) checkRegistration(); });
        checkRegistration();
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        setContentView(scroll);
    }

    private MaterialButton register;

    private void checkRegistration() {
        String base = Relay.normalizeServer(String.valueOf(server.getText()));
        if (base.isEmpty()) return;
        Tasks.run(() -> DecoApp.get().api.registrationEnabled(base), on -> {
            if (base.equals(Relay.normalizeServer(String.valueOf(server.getText()))))
                register.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
        }, e -> register.setVisibility(android.view.View.GONE));
    }

    private TextInputEditText field(LinearLayout parent, String hint, int type) {
        TextInputLayout layout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        layout.setHint(hint);
        float r = Ui.dp(this, 14);
        layout.setBoxCornerRadii(r, r, r, r);
        TextInputEditText edit = Ui.input(layout, type);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        parent.addView(layout, lp);
        return edit;
    }

    private void login() {
        String base = Relay.normalizeServer(String.valueOf(server.getText()));
        String user = String.valueOf(username.getText()).trim();
        String pass = String.valueOf(password.getText());
        if (base.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Ui.toast(this, "请填写服务器、用户名和密码");
            return;
        }
        String key = String.valueOf(accessKey.getText()).trim();
        submit.setEnabled(false);
        submit.setText("登录中…");
        // 访问秘钥选填，不校验，登录成功后直接保存
        Tasks.run(() -> DecoApp.get().api.login(base, user, pass), cookie -> {
            DecoApp.get().prefs.saveLogin(base, user, cookie);
            DecoApp.get().prefs.setAccessKey(key);
            openMain();
        }, error -> {
            submit.setEnabled(true);
            submit.setText("登录");
            Ui.toast(this, "登录失败：" + error.getMessage());
        });
    }

    private AppUpdater updater;

    @Override protected void onResume() {
        super.onResume();
        if (updater == null && !isFinishing()) updater = new AppUpdater(this);
        if (updater != null) {
            updater.resume();
            updater.check(false);
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (updater != null) updater.close();
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
