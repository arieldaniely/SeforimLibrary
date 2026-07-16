package io.github.kdroidfilter.seforimlibrary.importer

import java.lang.reflect.InvocationTargetException

internal fun runWorker(args: List<String>): Int {
    return try {
        require(args.size >= 2) { "Worker requires a main class and property count" }
        val mainClass = args[0]
        val propertyCount = args[1].toInt()
        require(propertyCount >= 0 && args.size >= 2 + propertyCount) {
            "Invalid worker property count: ${propertyCount}"
        }

        args.subList(2, 2 + propertyCount).forEach { encoded ->
            val separator = encoded.indexOf('=')
            require(separator > 0) { "Invalid worker property: ${encoded}" }
            System.setProperty(encoded.substring(0, separator), encoded.substring(separator + 1))
        }
        val stageArguments = args.drop(2 + propertyCount).toTypedArray()
        val entryPoint = Class.forName(mainClass)
        val arrayMain = runCatching {
            entryPoint.getMethod("main", Array<String>::class.java)
        }.getOrNull()

        if (arrayMain != null) {
            arrayMain.invoke(null, stageArguments as Any)
        } else {
            entryPoint.getMethod("main").invoke(null)
        }
        0
    } catch (error: Throwable) {
        val cause = unwrapInvocationTarget(error)
        System.err.println("Worker failed: ${cause.message}")
        cause.printStackTrace(System.err)
        1
    }
}

private tailrec fun unwrapInvocationTarget(error: Throwable): Throwable {
    return if (error is InvocationTargetException && error.targetException != null) {
        unwrapInvocationTarget(error.targetException)
    } else {
        error
    }
}
