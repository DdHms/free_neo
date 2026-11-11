# Landing behavior investigation

Investigation date: 2026-11-11. These observations are from local controller
journals and received telemetry. Relative altitude is decoded telemetry; its
physical accuracy has not been independently verified.

| Recorded flight | Camera preview | First landing request | Observed flight-controller telemetry |
| --- | --- | --- | --- |
| First | Disabled | Acknowledged | Relative altitude decreased from about 0.6 m to 0.2 m at about 2 s; by about 5 s the reported altitude was 0, flying was false, and motors were off. |
| Second | Enabled | Acknowledged | Descended from about 0.5 m to 0.3 m, then remained there in the observed interval. The landing-confirmation-needed flag was observed. |
| Third | Enabled | Acknowledged | Stayed around 0.6–0.7 m in the observed interval; a different raw flight-mode code appeared. |
| Later | Enabled | Acknowledged; two later requests also acknowledged | Stayed around 0.6 m during the observed interval. |
| Latest | Disabled | Acknowledged; two later requests also acknowledged | Held around 0.6 m for the first five seconds and still reported flying with motors on around ten seconds. The operator reported landing manually. |

The first ordinary landing request in each of these flights used the same
inner DUML sender, receiver, command fields and one-byte landing payload. Each
was preceded by a neutral stick packet. The latest flight's repeated commands
were acknowledged but did not establish descent in the sampled interval.

The first and latest runs both had camera preview disabled and produced different
landing outcomes. Neither capture reported a fresh landing-confirmation-needed
flag during the first 13 seconds after the initial acknowledgement. Changes in lighting, landing surface,
obstacles, sensors, battery, or firmware behavior could also matter. The raw
flight-mode codes are not sufficiently verified here to assign a meaning.
An acknowledgement is not proof of a completed landing. The second flight's
confirmation-needed flag does not establish why the other flights hovered.

Do not treat the simulated retry tests as evidence that repeated landing
commands are safe or effective on this aircraft. Preserve flight captures and
incident records. A grounded comparison of session timing with preview on and
off can be performed without flight; any future flight comparison requires a
verified independent intervention method and the project's existing safety
supervision. Do not bypass landing protection or force landing to make this
comparison.
