package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * FICHA DE INSTALACIÓN de LENY TV.
 *
 * Muestra, leído del propio Android, exactamente lo que el instalador compara
 * cuando alguien intenta instalar o abrir la app:
 *
 *  · el identificador de la app (applicationId / nombre del paquete),
 *  · la versión visible y el código de versión (versionCode) de la copia que
 *    está instalada en ESTE teléfono, comparados con los de esta revisión,
 *  · el Android mínimo y el Android para el que está preparada,
 *  · la huella de la firma digital con la que está firmada,
 *  · el archivo del que se instaló (nombre, tamaño y fecha),
 *  · si hay OTRA copia de LENY TV instalada en el teléfono (el caso típico del
 *    icono viejo que responde «App no instalada»).
 *
 * Es informativo: no cambia nada de la app ni del reproductor.
 */
final class InstallInfo {

    /** Versión que debe mostrar esta compilación. Se compara con la instalada. */
    static final String EXPECTED_NAME = "1.55";
    static final long EXPECTED_CODE = 55L;

    private InstallInfo() {
    }

    /** Texto de la ficha, listo para la pantalla DIAGNÓSTICO. */
    static String report(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSTALACIÓN Y FIRMA (lo que Android ve de esta app)\n");
        if (context == null) {
            sb.append("· (todavía no hay datos del teléfono)\n");
            return sb.toString();
        }

        PackageManager pm = context.getPackageManager();
        String pkg = "";
        try {
            pkg = context.getPackageName();
        } catch (Throwable t) {
            pkg = "(desconocido)";
        }

        // Si el usuario está leyendo esto, la app está instalada y arrancando.
        sb.append("· veredicto: esta copia SÍ está instalada y ejecutándose en este teléfono.\n")
                .append("  (Si se puede leer esta pantalla, la instalación ha funcionado.)\n");
        sb.append("· identificador de la app (applicationId): ").append(pkg).append('\n');
        sb.append("· versión de esta revisión: ").append(EXPECTED_NAME)
                .append(" · código ").append(EXPECTED_CODE).append('\n');

        PackageInfo info = null;
        try {
            info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
        } catch (Throwable t) {
            info = null;
        }

        boolean older = false;
        if (info == null) {
            sb.append("· versión instalada: no se pudo leer del sistema\n");
        } else {
            String versionName = info.versionName == null ? "(sin nombre)" : info.versionName;
            long versionCode;
            try {
                versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            } catch (Throwable t) {
                versionCode = info.versionCode;
            }
            older = versionCode < EXPECTED_CODE;
            sb.append("· versión instalada: ").append(versionName)
                    .append(" · código ").append(versionCode);
            if (older) {
                sb.append("  ← COPIA ANTIGUA: reinstale el APK nuevo para tener la última corrección");
            }
            sb.append('\n');
            ApplicationInfo app = info.applicationInfo;
            if (app != null) {
                sb.append("· Android mínimo que acepta: API ").append(app.minSdkVersion).append('\n');
                sb.append("· Android para el que está preparada: API ").append(app.targetSdkVersion).append('\n');
                sb.append("· cabe en este teléfono (Android 11 = API 30): ")
                        .append(app.minSdkVersion <= Build.VERSION.SDK_INT ? "sí" : "NO").append('\n');
            }
            sb.append("· firma digital instalada (SHA-256, primeros bytes): ").append(signatureOf(info)).append('\n');
        }

        try {
            ApplicationInfo app = context.getApplicationInfo();
            String dir = app == null ? null : app.sourceDir;
            if (dir != null && dir.length() > 0) {
                File apk = new File(dir);
                sb.append("· archivo del que se instaló: ").append(apk.getName());
                sb.append(" · ").append(humanSize(apk.length()));
                sb.append(" · fecha ").append(stamp(apk.lastModified())).append('\n');
            } else {
                sb.append("· archivo del que se instaló: (el sistema no lo indica)\n");
            }
        } catch (Throwable t) {
            sb.append("· archivo del que se instaló: (no se pudo consultar)\n");
        }

        sb.append("· teléfono: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append("), ").append(Build.MANUFACTURER)
                .append(' ').append(Build.MODEL).append('\n');
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis != null && abis.length > 0) {
                sb.append("· procesador: ").append(abis[0]).append('\n');
            }
        } catch (Throwable ignored) {
        }
        try {
            boolean debugSigned = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            sb.append("· esta copia es de pruebas: ").append(debugSigned ? "sí" : "no").append('\n');
        } catch (Throwable ignored) {
        }

        sb.append("\nENTRADA DEL MENÚ (EL ICONO CON EL QUE SE ABRE LA APP)\n");
        sb.append(launcherEntry(context));

        sb.append("\nOTRAS COPIAS DE LENY TV EN ESTE TELÉFONO\n");
        sb.append(otherCopies(context));

        sb.append("\nQUÉ SIGNIFICA «APP NO INSTALADA»\n");
        sb.append("· Ese aviso lo da el INSTALADOR (o el icono de la pantalla de inicio), nunca el")
                .append(" reproductor ni la interfaz. Aparece en cuatro situaciones:\n");
        sb.append("  1. El icono del menú guarda el NOMBRE de la pantalla que tiene que abrir. Si ese")
                .append(" nombre era el de la entrada antigua (antes de la portada) y esa entrada estaba")
                .append(" cerrada, Android responde «App no instalada» aunque la app esté instalada; el")
                .append(" botón Abrir de Ajustes → Apps sí funcionaba porque usa la entrada nueva. Desde")
                .append(" esta revisión la entrada antigua vuelve a abrir la app (y muestra la portada).")
                .append(" Si el icono sigue fallando: mantenga pulsado ese icono → Quitar, y abra LENY TV")
                .append(" desde el cajón de aplicaciones. Quitar un icono NO borra listas ni ajustes.\n");
        sb.append("  1b. En el teléfono puede quedar el icono de OTRA copia (de otro empaquetado) ya")
                .append(" desinstalada: ese icono sí responde «App no instalada». Use el icono que abre")
                .append(" esta pantalla, que es la copia instalada (").append(pkg).append(").\n");
        sb.append("  1c. Si el teléfono tiene la app «congelada» o protegida por el sistema (HiOS de")
                .append(" TECNO: Ajustes → Aplicaciones → LENY TV → quitar «Congelar»/«Bloqueo de app»),")
                .append(" el lanzador tampoco puede abrirla. Abrir desde Ajustes → Abrir sí suele funcionar.\n");
        sb.append("  2. Ya hay una LENY TV instalada firmada con OTRA clave: Android no permite")
                .append(" actualizarla. Solución: desinstalar esa copia (Ajustes → Apps → LENY TV →")
                .append(" Desinstalar) e instalar el APK nuevo.\n");
        sb.append("  3. El código de versión del APK nuevo es MENOR que el de la copia instalada.")
                .append(" Esta revisión declara código ").append(EXPECTED_CODE)
                .append("; si arriba se ve un número mayor, avise.\n");
        sb.append("  4. Play Protect o el permiso «instalar apps desconocidas» del instalador bloquean")
                .append(" la instalación. Solución: permitir la instalación desde esa app y, si Play")
                .append(" Protect avisa, elegir «Instalar de todos modos».\n");
        sb.append("· Una instalación correcta de esta revisión muestra versión ").append(EXPECTED_NAME)
                .append(" y código ").append(EXPECTED_CODE).append(".\n");
        return sb.toString();
    }

    /**
     * Busca otras apps de la familia LENY TV instaladas en el teléfono. Es la
     * causa habitual de ver un icono «LENY TV» que responde «App no instalada»:
     * un icono de una copia antigua o de otro empaquetado del mismo proyecto.
     */
    /**
     * ENTRADA DEL MENÚ: comprueba con Android qué componentes de esta app
     * responden al icono del menú (MAIN + LAUNCHER) y si la entrada ANTIGUA, la
     * que usaban los iconos creados antes de la portada, todavía se puede abrir.
     *
     * Sirve para distinguir dos casos que desde fuera se parecen mucho:
     *  · el icono es de ESTA instalación y funciona;
     *  · el icono guardado por el lanzador apunta a una entrada que no se puede
     *    abrir, y entonces Android contesta «App no instalada».
     */
    private static String launcherEntry(Context context) {
        StringBuilder sb = new StringBuilder();
        String pkg;
        PackageManager pm;
        try {
            pkg = context.getPackageName();
            pm = context.getPackageManager();
        } catch (Throwable t) {
            return "· (no se pudo consultar la entrada del menú)\n";
        }

        List<ResolveInfo> list = null;
        try {
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_LAUNCHER);
            launch.setPackage(pkg);
            list = pm.queryIntentActivities(launch, 0);
        } catch (Throwable t) {
            list = null;
        }

        if (list == null) {
            sb.append("· el sistema no dejó consultar las entradas del menú de esta app.\n");
        } else if (list.isEmpty()) {
            sb.append("· PROBLEMA: esta app no declara ninguna entrada de menú.\n");
        } else if (list.size() == 1) {
            ActivityInfo ai = list.get(0).activityInfo;
            sb.append("· entrada del menú: ").append(ai == null ? "(desconocida)" : ai.name);
            sb.append("  ✓ única y accesible (así es como debe ser)\n");
        } else {
            sb.append("· el sistema ve ").append(list.size())
                    .append(" entradas de menú (lo correcto es 1; más de una crea iconos repetidos):\n");
            for (int i = 0; i < list.size(); i++) {
                ActivityInfo ai = list.get(i).activityInfo;
                sb.append("   - ").append(ai == null ? "(desconocida)" : ai.name).append('\n');
            }
        }

        // La entrada ANTIGUA: la que tenían los iconos creados antes de la portada.
        String oldName = pkg + ".MainActivity";
        try {
            ActivityInfo old = pm.getActivityInfo(new ComponentName(pkg, oldName), 0);
            sb.append("· entrada antigua del menú (").append(oldName).append("): ");
            if (old == null) {
                sb.append("no existe en esta copia\n");
            } else if (old.exported) {
                sb.append("accesible ✓ → si el icono viejo apunta aquí, la app se abre igualmente.\n");
            } else {
                sb.append("BLOQUEADA → un icono viejo que apunte aquí respondería «App no instalada».\n");
            }
        } catch (Throwable t) {
            sb.append("· entrada antigua del menú: no se pudo consultar en esta copia\n");
        }

        sb.append("· el icono del menú guarda el nombre EXACTO de la pantalla que abre: por eso un icono").
                append(" creado por una versión anterior puede dejar de funcionar aunque la app siga instalada.\n");
        sb.append("· abrir la app desde el icono NUNCA borra las listas ni los ajustes; solo desinstalar lo hace.\n");
        return sb.toString();
    }

    private static String otherCopies(Context context) {
        StringBuilder sb = new StringBuilder();
        String self;
        try {
            self = context.getPackageName();
        } catch (Throwable t) {
            return "· (no se pudo consultar la lista de apps)\n";
        }
        List<PackageInfo> all;
        try {
            all = context.getPackageManager().getInstalledPackages(0);
        } catch (Throwable t) {
            all = null;
        }
        if (all == null) {
            return "· (el sistema no dejó consultar la lista de apps)\n";
        }
        int found = 0;
        for (int i = 0; i < all.size(); i++) {
            PackageInfo p = all.get(i);
            if (p == null || p.packageName == null) continue;
            if (p.packageName.equals(self)) continue;
            String label = "";
            try {
                ApplicationInfo ai = p.applicationInfo;
                label = ai == null ? "" : String.valueOf(ai.loadLabel(context.getPackageManager()));
            } catch (Throwable ignored) {
                label = "";
            }
            String hay = (p.packageName + " " + label).toLowerCase(Locale.US);
            if (hay.indexOf("leny") < 0 && hay.indexOf("lenny") < 0) continue;
            found++;
            long code;
            try {
                code = Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
            } catch (Throwable t) {
                code = p.versionCode;
            }
            sb.append("· otra copia instalada: ").append(label.length() == 0 ? "(sin nombre)" : label)
                    .append(" · ").append(p.packageName)
                    .append(" · versión ").append(p.versionName == null ? "(sin nombre)" : p.versionName)
                    .append(" · código ").append(code).append('\n');
        }
        if (found == 0) {
            sb.append("· no hay otra app LENY TV instalada. Si en la pantalla de inicio todavía se ve")
                    .append(" un icono de LENY TV que al pulsarlo dice «App no instalada», es un resto de")
                    .append(" una copia borrada: quítelo de la pantalla de inicio.\n");
        } else {
            sb.append("· hay ").append(found)
                    .append(" copia(s) más. Si el icono que pulsa es alguno de esos, Android puede")
                    .append(" responder «App no instalada» o abrir una versión antigua: use la entrada")
                    .append(" de LENY TV que muestra esta pantalla (").append(self).append(").\n");
        }
        return sb.toString();
    }

    /** Huella corta y estable de la firma con la que está firmada la copia instalada. */
    private static String signatureOf(PackageInfo info) {
        try {
            Signature[] signatures = info == null ? null : info.signatures;
            if (signatures == null || signatures.length == 0) return "(el sistema no la muestra)";
            byte[] raw = signatures[0].toByteArray();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < hash.length; i++) {
                int value = hash[i] & 0xFF;
                if (value < 16) hex.append('0');
                hex.append(Integer.toHexString(value));
                if (i == 7) break;
            }
            return hex.toString();
        } catch (Throwable t) {
            return "(no se pudo calcular)";
        }
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0) return "(tamaño desconocido)";
        if (bytes < 1024L * 1024L) return (bytes / 1024L) + " KB";
        long tenths = (bytes * 10L) / (1024L * 1024L);
        return (tenths / 10L) + "," + (tenths % 10L) + " MB";
    }

    private static String stamp(long millis) {
        if (millis <= 0) return "(sin fecha)";
        try {
            return new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(new Date(millis));
        } catch (Throwable t) {
            return "(sin fecha)";
        }
    }
}
