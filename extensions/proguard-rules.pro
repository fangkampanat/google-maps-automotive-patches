-dontobfuscate
-dontoptimize
-keepattributes *
-keep class app.morphe.** {
  *;
}
-keep class com.google.** {
  *;
}
# Proguard can strip away kotlin intrinsics methods that are used by extension Kotlin code. Unclear why.
-keep class kotlin.jvm.internal.Intrinsics {
    public static *;
}
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn javax.lang.model.element.Modifier
# The android.car stand-in (car data for Maps on DiLink): Maps calls these by their real names.
-keep class android.car.** {
  *;
}
