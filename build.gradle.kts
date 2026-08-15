plugins {
	id("dev.kikugie.loom-back-compat")
	`maven-publish`
}

version = "${property("mod.version")}+${sc.current.version}"
group = property("mod.group") as String
base.archivesName = property("mod.id") as String

val requiredJava: JavaVersion = when {
	sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
	sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
	else -> JavaVersion.VERSION_17
}

fun scProperty(key: String): String = sc.properties[key]

fun resourceExpansionProps() = mapOf(
	"version" to project.version,
	"minecraft" to scProperty("mod.mc_compat"),
	"java" to requiredJava.majorVersion,
	"mixin_java" to "JAVA_${requiredJava.majorVersion}",
)

repositories {
	mavenCentral()
	maven {
		name = "Masa"
		url = uri("https://masa.dy.fi/maven")
	}
	maven {
		name = "SakuraRyoko"
		url = uri("https://masa.dy.fi/maven/sakura-ryoko")
	}
	maven {
		name = "TerraformersMC"
		url = uri("https://maven.terraformersmc.com/releases/")
	}
	maven {
		name = "FallenBreath"
		url = uri("https://maven.fallenbreath.me/releases")
	}
}

loom {
	splitEnvironmentSourceSets()

	mods {
		register("bedrock-miner-extra") {
			sourceSet(sourceSets.main.get())
			sourceSet(sourceSets.getByName("client"))
		}
	}
}

dependencies {
	fun fapi(vararg modules: String) {
		for (module in modules) {
			modImplementation(fabricApi.module(module, scProperty("deps.fabric_api")))
		}
	}

	minecraft("com.mojang:minecraft:${sc.current.version}")
	loomx.applyMojangMappings()

	modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")

	// The client command API v2 only exists on 1.19.4+; older versions use v1.
	val commandApi = if (sc.current.parsed >= "1.19.4") "fabric-command-api-v2" else "fabric-command-api-v1"
	fapi(
		commandApi,
		"fabric-events-interaction-v0",
		"fabric-lifecycle-events-v1",
		"fabric-networking-api-v1",
	)
	modRuntimeOnly("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}") {
		exclude(group = "net.fabricmc", module = "fabric-loader")
	}

	// modmenu is optional (compile-only) on every version: the modmenu
	// entrypoint class exists everywhere, so an older version with modmenu
	// installed simply reports "no config screen" when malilib is absent.
	modCompileOnly("com.terraformersmc:modmenu:${scProperty("deps.mod_menu")}")

	// malilib is optional (compile-only) on every supported version (1.19.4 ..
	// 26.2). The GUI code is isolated behind //? if >=1.19.4 and a runtime
	// isModLoaded check.
	if (sc.current.parsed >= "1.19.4") {
		modCompileOnly("fi.dy.masa.malilib:malilib-fabric-${scProperty("deps.malilib_mc")}:${scProperty("deps.malilib")}")
	}
}

tasks.processResources {
	val props = resourceExpansionProps()
	inputs.properties(props)

	filesMatching("fabric.mod.json") {
		expand(props)
	}
	filesMatching("*.mixins.json") {
		expand(props)
	}
}

tasks.named<ProcessResources>("processClientResources") {
	val props = resourceExpansionProps()
	inputs.properties(props)

	filesMatching("*.mixins.json") {
		expand(props)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = requiredJava.majorVersion.toInt()
}

java {
	withSourcesJar()

	sourceCompatibility = requiredJava
	targetCompatibility = requiredJava
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}
