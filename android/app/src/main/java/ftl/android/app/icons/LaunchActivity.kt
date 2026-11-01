package ftl.android.app.icons

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import ftl.android.app.MainActivity

/**
 * What every app icon opens. It hands over to the launcher in a task of its own and is gone at once, so no task of this app
 * is rooted in an icon's entry: Android closes a task whose root entry gets disabled, which would end whatever is open,
 * a running game included, each time the icon changes.
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        finish()
    }
}
