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

private val logger = LoggerFactory.getLogger("no.nav.helse.SpillAv")

fun main(args: Array<String>) {
    val env = System.getenv()
    val starttidspunkt = LocalDate.of(2020, 1, 1).atStartOfDay()
    val dryRun = true

    logger.info("args: ${args.toList()}")

    replay(env, starttidspunkt, dryRun)
}

private fun replay(env: Map<String, String>, starttidspunkt: LocalDateTime, dryRun: Boolean = false) {
    logger.info("starter replay (dryRun=$dryRun) av alle events fra og med $starttidspunkt")

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

    val antall = using(sessionOf(dataSource)) {
        it.run(queryOf("SELECT COUNT(1) FROM melding where opprettet >= ?", starttidspunkt).map {
            it.long(1)
        }.asSingle) ?: 0
    }

    logger.info("replayer $antall hendelser")

    var håndtertTotal = 0L
    var meldingerPerOutputCounter = 0L
    val meldingerPerOutput = antall / 100 // skriv fremdrift ca. 100 ganger, ca. hvert 1 %

    using(sessionOf(dataSource)) { session ->
        session.forEach(queryOf("SELECT * FROM melding where opprettet >= ? ORDER BY opprettet ASC", starttidspunkt)) { row ->
            håndtertTotal += 1
            meldingerPerOutputCounter += 1

            if (!dryRun) producer.send(ProducerRecord(env.getValue("KAFKA_RAPID_TOPIC"), row.string("fnr"), row.string("data")))

            if (meldingerPerOutputCounter >= meldingerPerOutput) {
                val donePercent = Math.round(håndtertTotal/antall.toDouble() * 100)
                logger.info("$donePercent % ferdig, $håndtertTotal av $antall håndtert. ${antall - håndtertTotal} gjenstående.")
            }
        }
    }

    logger.info("Ferdig!")
}

private fun String.readFile() =
    try {
        File(this).readText(Charsets.UTF_8)
    } catch (err: FileNotFoundException) {
        null
    }
