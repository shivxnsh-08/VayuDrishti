# Service contracts

Example payloads for the two internal endpoints between the Java backend and the Python
forecast/advice service. **Both** test suites load these files:

- `backend/src/test/java/com/saanscheck/ContractTest.java` checks the Java DTOs read and write exactly these fields.
- `forecast-service/tests/test_contracts.py` checks the Pydantic schemas accept and produce them.

Change a field on one side, and the other side's test fails until the example is updated.
