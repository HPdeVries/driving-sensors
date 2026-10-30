import { DrivingSensors } from 'driving-sensors';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    DrivingSensors.echo({ value: inputValue })
}
