package no.nav.helse.spillav

import kotliquery.queryOf
import kotliquery.sessionOf
import kotliquery.using
import no.nav.helse.spillav.DataSourceBuilder.Role
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import kotlin.math.floor

private val logger = LoggerFactory.getLogger("no.nav.helse.SpillAv")

fun main(args: Array<String>) {
    val env = System.getenv()

    val cliArgs = args.associate {
        val parts = it.split("=", limit = 2)
        check(parts.size == 2) { "argumenter må angis på formen <key>=<value>" }
        parts[0] to parts[1]
    }

    val dryRun = cliArgs["dryRun"]?.let { it.toLowerCase() != "false" } ?: true
    val starttidspunkt = cliArgs.getValue("starttidspunkt").let {
        try {
            LocalDateTime.parse(it)
        } catch (err: DateTimeParseException) {
            LocalDate.parse(it).atStartOfDay()
        }
    }
    val fraFil = cliArgs["fra-fil"]?.let { it.toLowerCase() == "true" } ?: false

    logger.info("args: ${args.toList()}")
    replay(env, fraFil, starttidspunkt, dryRun)
}

private fun replay(env: Map<String, String>, fraFil: Boolean, starttidspunkt: LocalDateTime, dryRun: Boolean = false) {
    logger.info("starter replay (dryRun=$dryRun, fraFil=$fraFil) av alle events fra og med $starttidspunkt")

    val dataSourceBuilder = DataSourceBuilder(env)
    val kafkaConfig = KafkaConfig(
        bootstrapServers = env.getValue("KAFKA_BOOTSTRAP_SERVERS"),
        username = "/var/run/secrets/nais.io/service_user/username".readFile(),
        password = "/var/run/secrets/nais.io/service_user/password".readFile(),
        truststore = env["NAV_TRUSTSTORE_PATH"],
        truststorePassword = env["NAV_TRUSTSTORE_PASSWORD"]
    )

    val dataSource = dataSourceBuilder.getDataSource(Role.ReadOnly)

    val serializer = StringSerializer()
    val producer = KafkaProducer(kafkaConfig.producerConfig(), serializer, serializer)

    val meldinger = if (fraFil) lesMeldingerFraFil() else emptyList()

    val antall = using(sessionOf(dataSource)) {
        val query = if (fraFil) {
            queryOf("SELECT COUNT(1) FROM melding where opprettet >= ? AND id IN(${meldinger.joinToString(transform = { "?" })})", starttidspunkt, *meldinger.toTypedArray())
        } else {
            queryOf("SELECT COUNT(1) FROM melding where opprettet >= ?", starttidspunkt)
        }
        it.run(query.map { it.long(1) }.asSingle) ?: 0
    }

    logger.info("replayer $antall hendelser")

    var håndtertTotal = 0L
    var meldingerPerOutputCounter = 0L
    val meldingerPerOutput = antall / 50 // skriv fremdrift ca. 50 ganger, ca. hvert 2 %

    using(sessionOf(dataSource)) { session ->
        while (håndtertTotal < antall) {
            val query = if (fraFil) {
                queryOf("SELECT * FROM melding where opprettet >= ? AND id IN(${meldinger.joinToString(transform = { "?" })}) ORDER BY opprettet ASC LIMIT 1000 OFFSET ?", starttidspunkt, *meldinger.toTypedArray(), håndtertTotal)
            } else {
                queryOf("SELECT * FROM melding where opprettet >= ? ORDER BY opprettet ASC LIMIT 1000 OFFSET ?", starttidspunkt, håndtertTotal)
            }

            session.forEach(query) { row ->
                håndtertTotal += 1
                meldingerPerOutputCounter += 1

                if (!dryRun) producer.send(
                    ProducerRecord(
                        env.getValue("KAFKA_RAPID_TOPIC"),
                        row.string("fnr"),
                        row.string("data")
                    )
                )

                if (meldingerPerOutputCounter >= meldingerPerOutput) {
                    meldingerPerOutputCounter = 0
                    val donePercent = floor(håndtertTotal / antall.toDouble() * 1000) / 10
                    logger.info("$donePercent % ferdig, $håndtertTotal av $antall håndtert. ${antall - håndtertTotal} gjenstående.")
                }
            }
        }
    }

    producer.flush()

    logger.info("100 % ferdig, $håndtertTotal av $antall håndtert. ${antall - håndtertTotal} gjenstående.")
}

private fun lesMeldingerFraFil() = "/meldinger.txt".readResource()?.lineSequence()?.filter(String::isNotBlank)?.mapNotNull(String::toLongOrNull)?.toList() ?: emptyList()

private fun String.readResource(): String? = object {}.javaClass.getResource(this).readText()

private fun String.readFile() =
    try {
        File(this).readText(Charsets.UTF_8)
    } catch (err: FileNotFoundException) {
        null
    }
