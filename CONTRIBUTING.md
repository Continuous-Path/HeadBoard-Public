# Contributing to HeadBoard

Thanks for your interest. HeadBoard is built by the Continuous Path Foundation, a
501(c)(3) nonprofit, for people who find ordinary touchscreens difficult or
impossible to use. That purpose shapes what we accept: **accessibility,
performance on inexpensive hardware, and backwards compatibility outrank
novelty.** A change that is elegant but drops older devices is usually the wrong
trade here.

## Signing off your work (DCO)

We do not ask you to sign a contributor licence agreement. Instead we use the
[Developer Certificate of Origin](https://developercertificate.org/) — a short
statement that you wrote the contribution, or otherwise have the right to submit
it under the project's licence.

Certify it by adding a `Signed-off-by` line to each commit:

```
Signed-off-by: Jane Doe <jane@example.com>
```

`git commit -s` adds it for you. Use your real name and an address where you can
be reached. By signing off you agree to the DCO, reproduced in full at the link
above.

## Licence

HeadBoard is Apache-2.0. Contributions are accepted under that licence.

## Before you open a pull request

Build and test the app module:

```bash
cd HeadBoard && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

HeadBoard talks to JustType and to the OpenBoard-HB keyboard over broadcast
Intents. If you touch the action names, the custom permissions or the package
id, all three repositories have to move together — changing one silently breaks
head tracking.

Please also:

* Keep the change focused. One concern per pull request reviews far better.
* Match the surrounding code — its naming, its comment density, its idioms.
* Explain *why* in the commit message. The diff already shows what changed.
* Say what you tested, and on which device or Android version. "Tested on a
  Pixel Tablet, Android 15" is worth more than "works".

## Reporting bugs

Tell us what you expected, what happened, and how to reproduce it. For input or
prediction bugs, the exact key sequence and the on-screen result are the useful
details. Include the build identity shown at the foot of the settings screen —
it names the commit your build came from.

## Security and licensing

Please do not open a public issue for a security problem. Write to
**security@continuouspath.org** instead.

## Code of conduct

Participation is governed by our [Code of Conduct](./CODE_OF_CONDUCT.md).
Concerns go to **conduct@continuouspath.org**.
