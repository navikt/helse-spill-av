spill-av [![Actions Status](https://github.com/navikt/helse-spill-av/workflows/master/badge.svg)](https://github.com/navikt/helse-spill-av/actions)
=============

Leser meldinger fra databasen til spedisjon og produserer dem til en topic, konfigurert med `KAFKA_TARGET_TOPIC`.

# Kjøring

Finn siste versjon av docker image: 
https://github.com/navikt/helse-spill-av/packages/148909

1. Bytt ut `{{version}}` med versjonsnr
2. Slett `spedisjon` med `k delete app spedisjon` i det aktuelle klusteret (prod-fss/dev-fss)
3. Endre `args` hvor `dryRun=false` for å sende meldingene på kafka, og sett `starttidspunkt` til enten et dato+klokkeslett eller dato.
    Feks: `2020-01-01T12:00:00` eller `2020-02-01`
4. Deploy jobben med `k apply -f deploy/dev.yml` eller `k apply -f deploy/prod.yml` avhengig av kluster
5. Følg med på log output: finn podden med `k get pods -n tbd`. Tail slik: `k logs -f -n tbd <pod>`
6. Start/deploy `spedisjon` når jobben er fullført

Man kan også angi `fra-fil=true` som argument: da vil jobben lese meldingsIDer fra `meldinger.txt`

# Henvendelser

Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på GitHub.

## For NAV-ansatte

Interne henvendelser kan sendes via Slack i kanalen #område-helse.
