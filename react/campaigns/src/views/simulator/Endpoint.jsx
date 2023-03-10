import React, { useEffect, useState }   from 'react';

import {
    Card,
    Col,
    Row,
    CardHeader,
    Input,
    InputGroup,
    InputGroupAddon,
    InputGroupText,

 } from 'reactstrap';
 import { useViewContext } from "../../ViewContext";

const Endpoint = (props) => {
    const vx = useViewContext();
    
    const [url, setUrl] = useState(props.vars.url);

    const style = {
        backgroundColor: 'yellow',
        font: 'inherit',
        border: '4x solid blue',
        padding: '1px',
        cursor: 'pointer'

    }

    const  updaterRoot = (event, id) => {
        setUrl(event.target.value);
        props.vars.url = event.target.value;
    }

    // {props.rootHandler}

    const optionItems = props.vars.exchanges.map((exchange,index) =>
        <option selected={exchange.name===props.ssp} key={"exchange-select" + index}>{exchange.name}</option>
    );

    return (
        <Card  text="white" style={{ width: '100%' }}>
        <CardHeader>
          <h5 className="title">Send Bids/Wins to RTB Server</h5>
        </CardHeader>
        <Row>
            <Col xs="3">
                <InputGroup >
                    <InputGroupAddon addonType="prepend">
                        <InputGroupText>Root</InputGroupText>
                    </InputGroupAddon>
                    <Input value={url} onChange= {updaterRoot}   />
                </InputGroup>
            </Col>
            <Col xs="2">
                <select style={style} onChange={props.exchangeHandler} width='100%'>
                    {optionItems}
                </select>
            </Col>
            <Col xs="5">
                <InputGroup>
                    <InputGroupAddon addonType="prepend">
                        <InputGroupText>Endpoint</InputGroupText>
                    </InputGroupAddon>
                    <Input
                        value={props.vars.url + props.vars.uri}
                        onChange= {updaterRoot}
                        id='endpoint' />
                </InputGroup>
            </Col>
        </Row>
    </Card>
    );
};

export default Endpoint;